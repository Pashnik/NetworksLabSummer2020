package programs

import algebras.{Downloader, FileSystem}
import algebras.Extractor.{CssUri, HtmlResource, ImgUri, JsUri, LinkUri}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import domain.page.HtmlContent
import fs2._
import org.http4s.Uri
import org.slf4j.Logger
import algebras.Extractor.syntax._
import utils.XmlTraversable

trait Crawler[F[_]] {
  def crawl: Stream[F, Unit]
}

object Crawler {
  case class Start(uri: Uri)
  def apply[F[_]: Concurrent: Timer](fetcher: Downloader[F], fs: FileSystem[F])(
        startUri: Start
  )(implicit L: Logger): Crawler[F] =
    new Crawler[F] {
      type Index       = List[LinkUri]
      type Fetch[G[_]] = Ref[G, Index] // resources we are going to fetch

      def crawl: Stream[F, Unit] =
        Stream
          .eval(Ref.of[F, Index](List.empty))
          .flatMap { index =>
            def accumulate: Stream[F, Unit] = {
              def pipeline =
                Stream
                  .eval(index.get)
                  .map(_.head)
                  .flatMap { link =>
                    start(index, link)
                      .onFinalize(
                          index.update(_.filterNot(_ == link))
                      )
                  } ++ goIfNonEmpty

              def goIfNonEmpty: Stream[F, Unit] =
                Stream.eval(index.get).flatMap { list =>
                  if (list.nonEmpty) pipeline
                  else Stream.empty
                }

              goIfNonEmpty
            }

            start(index, LinkUri(startUri.uri)) ++ accumulate
          }

      def fetch(link: HtmlResource)(continue: HtmlContent => Stream[F, Unit]): Stream[F, Unit] =
        Stream
          .eval(fetcher.fetchPage(link.uri))
          .flatMap(
              _.fold(
                Stream
                .raiseError[F](new RuntimeException("Can't fetch page")): Stream[F, Unit]
            )(continue)
          )

      def start(index: Fetch[F], currentLink: HtmlResource): Stream[F, Unit] =
        fetch(currentLink) { content =>
          val pipeline =
            for {
              resources <- extract(content)
              nextLinks = resources.collect { case link: LinkUri => link }
              _              <- index.update(l => (l ++ nextLinks).distinct) // so that there are no endless recursive situations
              updatedContent <- XmlTraversable.modify[F](content)(resources)
            } yield updatedContent -> resources

          Stream.eval(pipeline).flatMap {
            case (content, resources) =>
              val name =
                if (currentLink.uri == startUri.uri) currentLink.toIndex
                else currentLink.toPath

              fs.writeFile(name, content) ++
                Stream
                  .emits(resources)
                  .covary[F]
                  .collect {
                    case r: HtmlResource
                        if r.isInstanceOf[JsUri] ||
                          r.isInstanceOf[CssUri] ||
                          r.isInstanceOf[ImgUri] =>
                      r
                  }
                  .map(r => fetch(r)(fs.writeFile(r.toPath, _)))
                  .parJoinUnbounded
          }
        }

      val extract: HtmlContent => F[List[HtmlResource]] =
        html =>
          for {
            styles  <- html.extract[F, CssUri]
            scripts <- html.extract[F, JsUri]
            links   <- html.extract[F, LinkUri]
            imgs    <- html.extract[F, ImgUri]
          } yield (styles ++ scripts ++ links ++ imgs).distinct
            .collect { case res: HtmlResource if res.uri.host.isDefined && res.uri.scheme.isDefined => res }
    }
}
