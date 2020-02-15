//#full-example
package com.example


import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import com.example.Cache.{CachedDeviceIds, Get}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor.typed.scaladsl.AskPattern._

case class RequestId(s: String)
case class DeviceId(s: String)

object Cache {
  final case class Get(requestId: RequestId, replyTo: ActorRef[CachedDeviceIds])
  final case class CachedDeviceIds(devices: List[DeviceId])

  def cached(devices: List[DeviceId]): Behavior[Get] =
    Behaviors.receive { (context, message) =>
      context.log.info("Cache request for requestId {}.", message.requestId)
      message.replyTo ! CachedDeviceIds(devices)
      Behaviors.same
    }
}

object SimpleCache extends App {
  val deviceIds = List(
    DeviceId("27ba0ec2-bd08-4473-bb3a-759bf76890ca"),
    DeviceId("e3056593-c56d-4fee-8e05-d1d6918a4f21"),
    DeviceId("6fefd14c-c5b2-498e-a669-0b70d414c9cb")
  )

  val cache = ActorSystem[Get](Cache.cached(deviceIds), "cache")

  implicit val timeout: Timeout             = 5.seconds
  implicit val scheduler: Scheduler         = cache.scheduler
  implicit val ec: ExecutionContextExecutor = cache.executionContext

  val requestId = RequestId("12345")

  val result: Future[CachedDeviceIds] = cache.ref.ask(ref => Cache.Get(requestId, ref))

  result.onComplete {
    case Success(_) => println("Devices!")
    case Failure(_) => println("Failure!")
  }
}
