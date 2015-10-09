package edu.luc.cs.metrics.ingestion.service

import spray.json.DefaultJsonProtocol._
import spray.json._
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.concurrent.Future
import java.net.URLEncoder
/**
 * Created by sshilpika on 6/30/15.
 */


object commitIssueCollection extends ingestionStrategy with Ingestion {

  def apply(user: String, repo: String, branch: String, metricType: String, accessToken: Option[String], path:Option[String], page: Option[String], cUrl:String): Future[String] = {

    metricType match {

      case "FilePaths" =>
        val repoFilePathsFuture = getHttpResponse(url("https://api.github.com/repos/"+user+"/"+repo+"/git/trees/"+branch+"?recursive=1", None),rawHeaderList(accessToken), 60.seconds)
        //println("INFILE PATHS:")
        repoFilePathsFuture.flatMap(repoFilePaths => {
          //println("REPO FILE PATHS"+ repoFilePaths.entity.data.asString)
          val g = repoFilePaths.entity.data.asString.parseJson.asJsObject.getFields("tree").flatMap{
            trees =>
              trees.convertTo[List[JsValue]].filter {
                tree => // filter out blobs
                  println(tree.asJsObject.fields.values.toList)
                  tree.asJsObject.fields.values.toList.map(_ compactPrint).contains("\"blob\"")
              } map {
                blob => blob.asJsObject.getFields("path")(0).compactPrint.replaceAll("\"","")
              }
          }
          val g1 = g map(path => this.apply(user, repo, branch, "Commits", accessToken, Option(path), None,""))
          Future.sequence(g1) map(_.toString)
        })


      case "Commits" =>
        //implicit val timeout = timeout(60.seconds)
        println("Commits now")
        /*val urlC = if(path.isDefined)
          "https://api.github.com/repos/"+user+"/"+repo+"/commits?sha="+branch+"&path="+URLEncoder.encode(path.get, "UTF-8")
        else
          "https://api.github.com/repos/"+user+"/"+repo+"/commits?sha="+branch*/
        val urlC1 = if(cUrl.isEmpty) "https://api.github.com/repos/"+user+"/"+repo+"/commits?sha="+branch else cUrl

        //val gitFileCommitList = getHttpResponse(url(urlC1, page), rawHeaderList(accessToken),600.seconds)
        val gitFileCommitList = getHttpResponse(urlC1, rawHeaderList(accessToken),600.seconds)

        val rateRemaining = gitFileCommitList.map(gitList => rateLimitCheck(gitList))

          //extracting urls from the commit info obtained and storing urlList in the DB

        rateRemaining.flatMap(rate => rate match {
          case 0 =>
            this.apply(user, repo, branch, "Commits", accessToken, path, page,cUrl)
          case _ =>
            gitFileCommitList.map(gitList => {
              dbStore(Commits(gitList.entity.data.asString.parseJson.convertTo[List[JsValue]], mongoCasbah(user + "_" + repo + "_" + branch + "_" + "URL")))
              //}
              //the url for next page of commits
              //println("nextUrlForCurrentWeek: "+getNextPage(gitList))
              getNextPageTemp(gitList)
              //}
            }) flatMap (nextUrl => nextUrl.map(page => {
              this.apply(user, repo, branch, "Commits", accessToken, path, Option(page), page)
            }).getOrElse(Future("")))
        })

      case "Issues" =>
        //implicit val timeout = timeout(60.seconds)
        val gitIssueList = getHttpResponse(url("https://api.github.com/repos/"+user+"/"+repo+"/issues?state=all", page),rawHeaderList(accessToken),60.seconds)

        gitIssueList.map(issueList => {
          rateLimitCheck(issueList)
          //extracting urls from the commit info obtained and storing urlList in the DB
          dbStore(Issues(issueList.entity.data.asString.parseJson.convertTo[List[JsValue]],mongoCasbah(user+"_"+repo+"_"+"Issues")))
          //the url for next page of commits
          println("nextUrlForCurrentWeek: "+getNextPage(issueList))
          getNextPage(issueList)
        }) flatMap(nextUrl => nextUrl.map(page =>{
          this.apply(user,repo,branch,metricType,accessToken,None,Option(page),"")
        }).getOrElse(Future("")))
    }


  }


}