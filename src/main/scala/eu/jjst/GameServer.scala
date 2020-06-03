package eu.jjst

import cats.data.Kleisli
import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import eu.jjst.OutputMessage.KeepAlive
import fs2.Stream
import fs2.concurrent.{ Queue, Topic }
import org.http4s.{ HttpRoutes, Request, Response }
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware._
import org.http4s.syntax.kleisli._

import scala.concurrent.duration._
import scala.util.Try

/*
 * Application entry point
 */
object ChatServer extends IOApp with LazyLogging {
  def run(args: List[String]): IO[ExitCode] = {
    // Get a tcp port that might be specified on the command line or in an environment variable
    val httpPort = args.headOption
      .orElse(sys.env.get("PORT"))
      .flatMap(s => Try(s.toInt).toOption) // Ignore any integer parse errors
      .getOrElse(8080)

    val initialMessage = KeepAlive
    for { // Synchronization objects must be created at a level where they can be shared with every object that needs them
      _ <- IO(logger.debug("Starting server"))
      queue <- Queue.unbounded[IO, InputMessage];
      topic <- Topic[IO, OutputMessage](initialMessage);

      // There are a few ways to represent state in this model. We choose functional references so that the
      // state can be referenced in multiple locations. If the state is only needed in a single location
      // then there are simpler models (like Stream.scan and Stream.mapAccumulate)
      ref <- Ref.of[IO, GameServerState](GameServerState(Map.empty));

      // Create and then combine the top-level streams for our application
      exitCode <- {
        // Stream for HTTP requests
        val httpStream = ServerStream.stream[IO](httpPort, ref, queue, topic)

        // Stream to keep alive idle WebSockets
        val keepAlive = Stream.awakeEvery[IO](30.seconds).map(_ => KeepAlive).through(topic.publish)

        // Stream to process items from the queue and publish the results to the topic
        // 1. Dequeue
        // 2. apply message to state reference
        // 3. Convert resulting output messages to a stream
        // 4. Publish output messages to the publish/subscribe topic
        val processingStream =
          queue.dequeue
            .evalTap { msg =>
              IO(logger.debug(s"Received input message: $msg"))
            }
            .evalMap(msg => ref.modify(_.update(msg)))
            .flatMap(Stream.emits)
            .evalTap { msg =>
              IO(logger.debug(s"Sending output message: $msg"))
            }
            .through(topic.publish)

        // fs2 Streams must be "pulled" to process messages. Drain will perpetually pull our top-level streams
        Stream(httpStream, keepAlive, processingStream).parJoinUnbounded.compile.drain
          .as(ExitCode.Success)
      }
    } yield exitCode
  }
}

object ServerStream {
  // Builds a stream for HTTP events processed by our router
  def stream[F[_]: ConcurrentEffect: Timer: ContextShift](
    port: Int,
    games: Ref[F, GameServerState],
    queue: Queue[F, InputMessage],
    topic: Topic[F, OutputMessage]): fs2.Stream[F, ExitCode] = {
    val routes = Logger.httpRoutes(logHeaders = true, logBody = true)(CORS(new GameRoutes[F](games, queue, topic).routes))
    BlazeServerBuilder[F]
      .bindHttp(port, "0.0.0.0")
      .withHttpApp(
        Router("/" -> routes).orNotFound)
      .serve
  }
}
