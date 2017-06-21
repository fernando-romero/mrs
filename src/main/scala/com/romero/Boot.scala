package com.romero

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.romero.actor.Manager
import com.romero.http.Router
import com.romero.service.{ImdbServiceImpl, PersistenceServiceImpl}
import com.typesafe.config.ConfigFactory

object Boot extends App with Router {
  implicit val system = ActorSystem("mrs")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val conf = ConfigFactory.load
  val mongoUri = conf.getString("mongoUri")
  val interface = conf.getString("interface")
  val port = conf.getInt("port")

  val imdbService = new ImdbServiceImpl()
  val persistenceService = new PersistenceServiceImpl(mongoUri)
  val manager = system.actorOf(Manager.props(persistenceService, imdbService))

  Http().bindAndHandle(route, interface, port)
}