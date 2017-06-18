name := "akka-quickstart-scala"

version := "1.0"

scalaVersion := "2.12.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.0.6",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.6",
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.1.0",
  "org.specs2" %% "specs2-core" % "3.8.9"
)
