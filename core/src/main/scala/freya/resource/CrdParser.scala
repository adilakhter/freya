package freya.resource

import cats.effect.Sync
import cats.implicits._
import com.fasterxml.jackson.databind.ObjectMapper
import freya.watcher.AnyCustomResource
import freya.{CustomResourceParser, JsonReader}

import scala.util.{Failure, Success, Try}

private[freya] object CrdParser {
  def apply[F[_]: Sync](): F[CrdParser] =
    Sync[F].delay(new CrdParser)
}

private[freya] class CrdParser extends CustomResourceParser {
  private val mapper = new ObjectMapper

  def parse[T: JsonReader, U: JsonReader](cr: AnyCustomResource): Either[Throwable, (T, Option[U])] = {
    for {
      spec <- parseProperty[T](cr.getSpec, "spec")
      status <- Option(cr.getStatus) match {
        case None => None.asRight[Throwable]
        case Some(s) => parseProperty[U](s, "status").map(Some(_))
      }
    } yield (spec, status)
  }

  private def parseProperty[T: JsonReader](property: AnyRef, name: String) = {
    val read = implicitly[JsonReader[T]]
    val json = property match {
      case str: String => str
      case _ => mapper.writeValueAsString(property)
    }
    val parsed = read.fromString(json)
    lazy val clazz = read.targetClass

    parsed match {
      case Right(s) =>
        if (s == null) { // empty spec
          Try {
            val emptySpec = clazz.getDeclaredConstructor().newInstance()
            Right(emptySpec)
          } match {
            case Success(s) => s
            case Failure(e: InstantiationException) =>
              val msg = s"Failed to instantiate ${clazz.getCanonicalName} from CRD.$name"
              Left(new RuntimeException(msg, e))
            case Failure(e: IllegalAccessException) =>
              val msg = s"Failed to instantiate ${clazz.getCanonicalName} from CRD.$name"
              Left(new RuntimeException(msg, e))
            case Failure(e) =>
              Left(new RuntimeException(s"Failed to parse ${clazz.getCanonicalName} from CRD.$name", e))
          }
        } else
          Right(s)
      case Left(t) =>
        val msg = s"Failed to convert CRD.$name to ${clazz.getCanonicalName}"
        Left(new RuntimeException(msg, t))
    }
  }
}
