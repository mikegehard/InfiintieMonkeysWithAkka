package com.typesafe.playmini

import play.api.mvc.{Action, AsyncResult}
import play.api.mvc.Results._
import play.api.libs.concurrent._

import scala.util.Random
import annotation.tailrec

import akka.actor.{Props, ActorSystem, Actor}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.{Await, Future}
import com.typesafe.play.mini.{POST, Path, GET, Application}
import play.api.data.Form
import play.api.data.Forms._

/**
 * Inspiration taken from http://en.wikipedia.org/wiki/Infinite_monkey_theorem
 */
object PlayMiniSample extends Application {
  lazy val system = ActorSystem("ShakespeareGenerator")
  lazy val shakespeare = system.actorOf(Props[ShakespeareActor], "shakespeare")
  implicit val timeout = Timeout(5000 milliseconds)
  
  def route = {
    case GET(Path("/ping")) => Action { Ok("Pong @ %s\n".format(System.currentTimeMillis)) }
    case POST(Path("/write")) ⇒ Action { implicit request =>
      val start = System.nanoTime
      val numberOfWordsToTry = writeForm.bindFromRequest.get

      AsyncResult {
        (shakespeare ? numberOfWordsToTry).mapTo[Result].asPromise.map { result ⇒
          // We have a result - make some fancy pantsy presentation of it
          val builder = new StringBuilder
          builder.append("SHAKESPEARE WORDS:\n")
          result.shakespeareMagic.foreach { w => builder.append(w + "\n") }
          builder.append("UNWORTHY WORDS CREATED: " + result.unworthyWords.size + "\n")
          builder.append("In " + (System.nanoTime - start)/1000 + "us\n")
          Ok(builder.toString)
        }
      }
    }
  }

  val writeForm = Form("numberOfWordsToTry" -> number(min = 1))
}

case class Result(shakespeareMagic: Set[String], unworthyWords: Set[String])

class ShakespeareActor extends Actor {
  import ShakespeareActor._

  lazy val randomGenerator = new Random
  implicit val timeout = Timeout(5000 milliseconds)

  import context.dispatcher

  def receive = {
    case numberOfWordsToTry: Int =>
      val wordsPerMonkey = 1000
      val numberOfMonkeys = numberOfWordsToTry / wordsPerMonkey
      val futures = for (x <- 1 to numberOfMonkeys) yield {
        context.actorOf(Props[MonkeyWorker]) ? wordsPerMonkey mapTo manifest[Set[String]]
      }

      Future.sequence(futures) map { wordSets =>
        val mergedSet = wordSets reduce ( (a, b) => a ++ b )
        val (shakespeare, unworthy) = mergedSet partition (x => Blueprint.contains(x))
        Result(shakespeare, unworthy)
      } pipeTo sender
  }
}

object ShakespeareActor {
  lazy val Blueprint = Set("to", "be", "or", "not")
}

class MonkeyWorker extends Actor {
  import WorkerActor._

  lazy val randomGenerator = new Random
  
  def receive = {
    // We simplify the word generation by saying that every word can be between 1 and 26 letters.
    // If in real life, i.e. when using monkeys, some statistical bias would be useful when handing out the
    // type writing instructions.
    case tries: Int =>
      var words = Set.empty[String]
      1 to tries foreach { x => words += generateWork(randomGenerator.nextInt(Letters.size)) }
      sender ! words
  }

  def generateWork(letters: Int) = {
    @tailrec
    def trGeneration(letterNumber: Int, result: String): String = letterNumber match {
      case 0 => result.reverse
      case n => trGeneration(n - 1, result + Letters(randomGenerator.nextInt(Letters.size - 1)))
    }

    trGeneration(letters, "")
  }
}

object WorkerActor {
  lazy val Letters = ('a' to 'z').toSeq
}
