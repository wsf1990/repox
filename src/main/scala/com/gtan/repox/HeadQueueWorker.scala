package com.gtan.repox

import akka.actor._
import io.undertow.server.HttpServerExchange
import org.w3c.dom.html.HTMLScriptElement

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object HeadQueueWorker {
  case class NotFound(exchange: HttpServerExchange)
  case class FoundIn(repo: Repo, headers: Repox.ResponseHeaders, exchange: HttpServerExchange)
}

class HeadQueueWorker extends Actor with Stash with ActorLogging {
  import HeadQueueWorker._

  override def receive = idle

  var found = false
  var resultHeaders: Repox.ResponseHeaders = _

  def idle: Receive = {
    case Requests.Head(exchange) =>
      if(Repox.downloaded(exchange.getRequestURI)){
        Repox.immediateHead(exchange)
        self ! PoisonPill
      } else {
        context.actorOf(Props(classOf[HeadMaster], exchange), name = s"HeadMaster_${Random.nextInt()}")
        context become working
      }
  }

  def working: Receive = {
    case Requests.Head(_) =>
      stash()
    case result @ FoundIn(repo, headers, exchange) =>
      found = true
      resultHeaders = headers
      Repox.respondHead(exchange, headers)
      unstashAll()
      context.setReceiveTimeout(1 second)
      context become flushWaiting
    case result @ NotFound(exchange) =>
      found = false
      Repox.respond404(exchange, cause = "Tried 3 times. Give up.")
      unstashAll()
      context.setReceiveTimeout(1 second)
      context become flushWaiting
  }

  def flushWaiting: Receive = {
    case Requests.Head(exchange) =>
      if(found)
        Repox.respondHead(exchange, resultHeaders)
      else
        Repox.respond404(exchange, cause = "Tried 3 times. Give up.")
    case ReceiveTimeout =>
      self ! PoisonPill
  }
}
