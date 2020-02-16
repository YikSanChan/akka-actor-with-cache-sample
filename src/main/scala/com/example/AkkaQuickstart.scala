package com.example

import akka.actor
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import com.example.Cache.Devices

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Random, Success}

case class RequestId(s: String)

case class DeviceId(s: String)

object Cache {
  sealed trait Command
  final case class Get(requestId: RequestId, replyTo: ActorRef[GetReply]) extends Command
  final case class Devices(devices: List[DeviceId]) extends Command

  sealed trait GetReply
  final case object EmptyCache extends GetReply
  final case class CachedDevices(devices: List[DeviceId]) extends GetReply

  def apply(): Behavior[Command] =
    Behaviors.receive[Command] { (context, message) =>
      message match {
        case Get(requestId, replyTo) =>
          context.log.info("Empty cache request for requestId {}.", requestId)
          replyTo ! EmptyCache
          Behaviors.same
        case Devices(devices) =>
          context.log.info("Initializing cache.")
          cached(devices)
      }
    }

  private def cached(devices: List[DeviceId]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case Get(requestId, replyTo) =>
          context.log.info("Cache request for requestId {}.", requestId)
          replyTo ! CachedDevices(devices)
          Behaviors.same
        case Devices(updatedDevices) =>
          context.log.info("Updating cache.")
          cached(updatedDevices)
      }
    }
}

object Main {
  case class Ping()

  def apply(): Behavior[Ping] = Behaviors.setup {context =>
    import akka.actor.typed.scaladsl.adapter._
    implicit val untypedSystem: actor.ActorSystem = context.system.toClassic
    implicit val ec: ExecutionContextExecutor = untypedSystem.dispatcher
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val random = new Random()
    def getDevices: Future[List[DeviceId]] =
      Future.successful(List.fill(100)(random.nextInt(100).toString).map(DeviceId))
    val cache = context.spawn(Cache(), "cache")
    RestartSource
      .withBackoff(
        minBackoff = 0.seconds,
        maxBackoff = 60.seconds,
        randomFactor = 0.1
      ) { () =>
        Source
          .tick(initialDelay = 0.seconds, interval = 5.seconds, tick = ())
          .mapAsync(parallelism = 1) { _ =>
            getDevices
          }
          .map(devices => cache ! Devices(devices))
      }
      .runWith(Sink.ignore)
      .onComplete {
        case Failure(ex) => context.system.log.error("Failed to get devices : {}", ex)
        case Success(_) =>
      }
    Behaviors.same
  }
}

object SimpleCache extends App {
  val main: ActorSystem[Main.Ping] = ActorSystem(Main(), "main")
}
