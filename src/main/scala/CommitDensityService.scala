package edu.luc.cs499.scala.gitcommitdensity.service

import java.text.SimpleDateFormat

import akka.actor.{ActorSystem, Actor, Props}
import akka.event.Logging
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.http.HttpHeaders.RawHeader
import spray.http.MediaTypes._
import spray.routing.HttpService
import spray.http._
import spray.httpx.RequestBuilding._
import spray.json._
import DefaultJsonProtocol._
import spray.routing._
import util._
import concurrent.duration._
import concurrent.ExecutionContext.Implicits._
import scala.concurrent.{Await, Future}
import com.mongodb.casbah.Imports._
import java.time._
/**
 * Created by sshilpika on 6/30/15.
 */

trait CommitDensityMetrics /*extends App*//*extends HttpService*/{
  //val sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")




  def gitRepoStorage(user: String, repo:String, branch:String, startDate:String, accessToken: Option[String]): Future[String] ={
    println("INSIDE")
    //val future = Future {
      val instant = Instant.parse(startDate)//sdf.parse(startDate).toInstant
      val ldt = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"))
      val day_of_week = ldt.getDayOfWeek.getValue
      val days_left = 7 - day_of_week
      val until_first = if (days_left ==0) instant.plus(java.time.Duration.ofHours(24)) else instant.plus(java.time.Duration.ofDays(days_left))
      val instant_now = Instant.now() //LocalDateTime.now(ZoneId.of("UTC"))
      val strBuilder = new StringBuilder("")
      var result: List[Future[String]] = Nil
      accessToken foreach println
      var since = instant
      var until = until_first
      while (until.compareTo(instant_now) < 0) {
        val r = gitRepoCommitsPerWeek(user, repo, branch, since.toString, until.toString, accessToken, None)

        /*r.onComplete{
          case Success =>
          case Failure(value) => println("Call to commits failed with error: "+value)
        }*/

        Await.result(r, 3.days)
        result :+ r
        since = until
        until = since.plus(java.time.Duration.ofDays(7))
        println("since is:"+ since)
        println("until is:"+until)


      }
      Future.sequence(result).map(_.toString())
    //}
    //future.flatMap(x => x)
  }
  implicit val system = ActorSystem("gitCommitDensity")
  def gitRepoCommitsPerWeek(user: String, repo: String, branch:String, since:String, until: String, accessToken: Option[String], page: Option[String]): Future[String] ={

    //val service = system.actorOf(Props[MyServiceActor],"git-file-commit-density")
    //import system.dispatcher
    val log = Logging(system, getClass)
    implicit val timeout = Timeout(60.seconds)

    val rawHeadersList = accessToken.foldLeft(Nil: List[RawHeader])((list, token) => list:+RawHeader("Authorization", "token "+token))
   // println(rawHeadersList)
    val url = if(page.isDefined){"https://api.github.com/repos/"+user+"/"+repo+"/commits?sha="+branch+"&since="+since+"&until="+until+"&"+page} else "https://api.github.com/repos/"+user+"/"+repo+"/commits?sha="+branch+"&since="+since+"&until="+until

    val gitFileCommitList =
      (IO(Http) ? Get(url).withHeaders(rawHeadersList)).mapTo[HttpResponse]

    //println("before urls")

    val nextUrlForCurrentWeek = gitFileCommitList.map(gitList => {
      println("This is a list of headers:")
      val link = gitList.headers.filter(x => x.name.equals("Link"))
      gitList.headers.map(x => println(x.name+ "!!!!!!!!!!!!!!!!" +x.value))
      println(link)
      val rateTime =  gitList.headers.filter(x => x.name.equals("X-RateLimit-Reset"))(0).value
      val rateRemaining = gitList.headers.filter(x => x.name.equals("X-RateLimit-Remaining"))(0).value
      val inst = Instant.ofEpochSecond(rateTime.toLong)
      if(rateRemaining == 0){
        println("Sleeping Rate Limit = 0")
        Thread.sleep(inst.toEpochMilli)
      }
      else if(rateRemaining.toInt%1000 == 0){
        Thread.sleep(60000)
      }
      val nextUrlForCurrentWeek = Option(link(0)).map(x =>{
        val i = x.value.indexOf("page")
        x.value.substring(i,i+6)
      })


      println("nextUrlForCurrentWeek: "+nextUrlForCurrentWeek)
      nextUrlForCurrentWeek
    })

    val shaList = gitFileCommitList.map(gitList =>{


      gitList.entity.data.asString.parseJson.convertTo[List[JsValue]].foldLeft(Nil:List[JsValue]) { (tuplesList, commits) =>
        tuplesList ++ commits.asJsObject.getFields("url")
      }
      }
    )
    //println("before GetCommit LOC")
    val result = getCommitLOC(repo, shaList,since,until,rawHeadersList, system)


    val result1 = nextUrlForCurrentWeek.flatMap(nextUrl => nextUrl.map(url =>{
      gitRepoCommitsPerWeek(user,repo,branch,since,until,accessToken,Option(url))
    } ).getOrElse(Future("")))

    result.fallbackTo(result1)

  }


  import java.time.temporal.ChronoUnit
  import java.time.temporal.Temporal

  def getCommitLOC(reponame: String, urlList:Future[List[JsValue]],since:String, until:String, rawHeadersList:List[RawHeader], system:ActorSystem ): Future[String] ={
    implicit val actorSys = system
    implicit val timeout = Timeout(60.seconds)
    urlList.flatMap(lis =>{


      //setting up mongo client
     // println("MONGO CLIENT")
      val mongoClient1 = MongoClient("localhost", 27017)
      //code for heroku upload
      /*var uri = MongoClientURI("mongodb://heroku_r7px9z20:3abv0h3itskhvpiumtfmi2e5d6@ds045882.mongolab.com:45882/heroku_r7px9z20")
      val mongoClient =  MongoClient(uri)
      val db = mongoClient("heroku_r7px9z20")
*/
      // code for localhost Mongo instance
      val mongoClient = MongoClient("localhost",27017)
      val db = mongoClient(reponame)

      val collName = LocalDateTime.ofInstant(Instant.parse(since),ZoneId.of("UTC"))
        val collDay = collName.getDayOfYear
      val collYear = collName.getYear
      val coll = db("COLL"+collYear+"DAY"+collDay)
     //println("after creation")
      Future.sequence(lis.map(url =>{
       //println(url+"URl")
        val gitFileCommitList =
          (IO(Http) ? Get(url.compactPrint.replaceAll("\"","")).withHeaders(rawHeadersList)).mapTo[HttpResponse]

        val tupleList = gitFileCommitList.map(commit =>{

          val rateTime =  commit.headers.filter(x => x.name.equals("X-RateLimit-Reset"))(0).value
          val rateRemaining = commit.headers.filter(x => x.name.equals("X-RateLimit-Remaining"))(0).value
          if(rateRemaining == 0){
            val inst = Instant.ofEpochSecond(rateTime.toLong)
            Thread.sleep(inst.toEpochMilli)
          }
          else if(rateRemaining.toInt%1000 == 0){
            Thread.sleep(60000)
          }


          val filesList = commit.entity.data.asString.parseJson.asJsObject.getFields("commit","files")
          //println("NUMMMMMM")
          val commitSha = commit.entity.data.asString.parseJson.asJsObject.getFields("sha")(0)
          //println(filesList(0)+"\n\n\n"+filesList(1))
          val fileName = filesList(1).convertTo[List[JsValue]]/*filter { x1 =>
            x1.asJsObject.getFields("filename").contains(("\"" + filePath + "\"").parseJson)
          }*/
          val date = filesList(0).asJsObject("HERE").getFields("committer")(0).asJsObject("HERE1").getFields("date")(0).compactPrint.replaceAll("\"","")

          val lisInts = fileName.foldLeft(Nil:List[(String,Int)]){(lis, v) => {
            val change = v.asJsObject.getFields("additions","deletions")
            //println(change+" CHNAGE")
            val loc = change(0).convertTo[Int]- change(1).convertTo[Int]
            val filename = v.asJsObject.getFields("filename")(0)
            val fileSha = v.asJsObject.getFields("sha")(0)
            val insert = MongoDBObject("date"-> date,"commitSha" -> commitSha.compactPrint,"loc" -> loc,"filename"->filename.compactPrint, "fileSha" -> fileSha.compactPrint,"since" -> since, "until" -> until )
            coll.insert(insert)
            //coll.find foreach println
            lis:+(filename+" sha = "+fileSha,loc)
          }
          }

          val inst = Instant.parse(date)

          (inst,lisInts)
        })
        tupleList

      })).map(a => {

        /*val lis1 = */a.sortBy(z => z._1).toString
        /*println(lis1+"LLLIIISSS")
        val lis2 = lis1.tail:+(sdf.parse(until).toInstant,a.last._2)
        println(lis2)
        val lis3 = lis1.zip(lis2)
        println(lis3+"lis3")
        val result = lis3.map(c => {

          (ChronoUnit.MILLIS.between(c._1._1,c._2._1),c._1._2)

        })
        val totalMillis = result.foldLeft(0L:Long){(l,z) => l+z._1}
          val finalResult = (result.map(z => z._1*z._2).sum)/totalMillis
          "(Date, LOC) = "+lis1.toString+"\nFinal Result = "+(finalResult.toDouble/1000)+" KLOC"*/
      }

        )})
  }


/*  val myRoute = path(Segment / Segment / Segment) { (user, repo, branch) =>
    get {
      respondWithMediaType(`text/plain`) {
        // XML is marshalled to `text/xml` by default, so we simply override here
        optionalHeaderValueByName("Authorization") { (accessToken) =>
          parameters('created) { (created) =>
            onComplete(gitRepoStorage(user, repo, branch, created, accessToken)) {
              case Success(value) => complete(value)
              case Failure(value) => complete("Request to github failed with value : " + value)
            }
          }
        }
      }
    }
  }*/

}
/*
class MyServiceActor extends Actor with CommitDensityMetrics {

  def actorRefFactory = context

  def receive = runRoute(myRoute)
}
*/
