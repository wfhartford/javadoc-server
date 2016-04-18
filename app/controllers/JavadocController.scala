package controllers

import java.io.{File, FileOutputStream}
import javax.inject.{Inject, Singleton}

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import play.api.libs.ws.{StreamedResponse, WSClient}
import play.api.mvc.{Action, Controller}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JavadocController @Inject()(ws: WSClient, implicit val mat: Materializer, implicit val ec: ExecutionContext) extends Controller {
  private val pathRegex = "([^:/]+):([^:/]+):([^:/]+)/(.*)".r

  private val repoUrl = "http://repo1.maven.org/maven2"
  private val javadocPath = s"${System.getProperty("user.home")}/.javadoc-server/javadoc"

  def javadoc(path: String) = Action {
    val pathRegex(groupId, artifactId, version, file) = path
    val artifactPath = s"${groupId.replace('.', '/')}/$artifactId/$version"
    val localArtifactPath = new File(s"$javadocPath/$artifactPath")
    val fileName = s"$artifactId-$version-javadoc.jar"
    if (!localArtifactPath.isDirectory) {
      val url = s"$repoUrl/$artifactPath/$fileName"
      val futureResponse: Future[StreamedResponse] = ws.url(url).withMethod("GET").stream()
      val downloadedFile: Future[File] = futureResponse.flatMap { res =>
        val jarFile = new File(localArtifactPath, fileName)
        jarFile.getParentFile.mkdirs()
        val outputStream = new FileOutputStream(jarFile)

        // The sink that writes to the output stream
        val sink = Sink.foreach[ByteString] { bytes: ByteString =>
          outputStream.write(bytes.toArray)
        }

        // materialize and run the stream
        res.body.runWith(sink).andThen {
          case result =>
            // Close the output stream whether there was an error or not
            outputStream.close()
            // Get the result or rethrow the error
            result.get
        }.map(_ => jarFile)
      }
    }
    if (localArtifactPath.isDirectory) {
      def targetPath = new File(localArtifactPath, file)
      if (targetPath.isFile) {
        Ok.sendFile(targetPath, inline = true)
      }
      else {
        Ok(views.html.index(s"Could not find file in javadoc archive: \ngroup: $groupId; artifact: $artifactId; version: $version; file: $file"))
      }
    }
    else {
      Ok(views.html.index(s"Could not find archive: \ngroup: $groupId; artifact: $artifactId; version: $version; file: $file"))
    }
  }
}
