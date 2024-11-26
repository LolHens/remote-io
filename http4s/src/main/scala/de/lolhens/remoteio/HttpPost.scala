package de.lolhens.remoteio

import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.kernel.Sync
import cats.syntax.all._
import de.lolhens.remoteio.HttpPost.HttpPostArgs
import de.lolhens.remoteio.Rpc.{LocalRpcImpl, LocalSerializableRpcImpl, Protocol, RemoteRpcImpl, RpcRoutes, SerializableRpc}
import org.http4s.client.Client
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Method, Request, Response, Status, Uri}

trait HttpPost extends Protocol[HttpPost] {
  override type Args[F[_], A, B] = HttpPostArgs[F, A, B]
}

object HttpPost extends HttpPost {
  final case class HttpPostRpcRepoId(id: String)

  abstract class HttpPostRpcRepo(id: String) {
    protected implicit def repoId: HttpPostRpcRepoId = HttpPostRpcRepoId(id)
  }

  final case class HttpPostArgs[F[_], A, B](repoId: HttpPostRpcRepoId,
                                            id: String,
                                            aCodec: HttpPostCodec[F, A],
                                            bCodec: HttpPostCodec[F, B])

  object HttpPostArgs {
    implicit def auto[F[_], A, B](implicit repoId: HttpPostRpcRepoId, name: sourcecode.Name,
                                  aCodec: HttpPostCodec[F, A], bCodec: HttpPostCodec[F, B]): HttpPostArgs[F, A, B] =
      HttpPostArgs(repoId, name.value, aCodec, bCodec)

    implicit def stringArgs[F[_], A, B](id: String)
                                       (implicit repoId: HttpPostRpcRepoId,
                                        aCodec: HttpPostCodec[F, A], bCodec: HttpPostCodec[F, B]): HttpPostArgs[F, A, B] =
      HttpPostArgs(repoId, id, aCodec, bCodec)
  }

  case class HttpPostRpcImpl[F[_] : Sync](client: Client[F], uri: Uri) extends RemoteRpcImpl[F, HttpPost] {
    override def run[A, B, Args](rpc: SerializableRpc[F, A, B, HttpPost], a: A): F[B] = {
      implicit val encoder: EntityEncoder[F, A] = rpc.args.aCodec.encoder
      implicit val decoder: EntityDecoder[F, B] = rpc.args.bCodec.decoder
      client.expect[B](Request[F](
        method = Method.POST,
        uri = uri / rpc.args.repoId.id / rpc.args.id
      ).withEntity(a))
    }
  }

  final case class HttpPostCodec[F[_], A](decoder: EntityDecoder[F, A],
                                          encoder: EntityEncoder[F, A])

  object HttpPostCodec extends HttpPostCodecLowPrioImplicits {
    implicit def unitEntityCodec[F[_] : Concurrent]: HttpPostCodec[F, Unit] =
      HttpPostCodec(EntityDecoder.void, EntityEncoder.unitEncoder)
  }

  trait HttpPostCodecLowPrioImplicits {
    implicit def entityCodec[F[_], A](implicit
                                      decoder: EntityDecoder[F, A],
                                      encoder: EntityEncoder[F, A]): HttpPostCodec[F, A] =
      HttpPostCodec(decoder, encoder)
  }

  implicit class RpcRoutesHttpPostOps[F[_]](routes: RpcRoutes[F, HttpPost]) {
    def toRoutes(implicit F: Sync[F]): HttpRoutes[F] = {
      def path[F[_], A, B](args: HttpPostArgs[F, A, B]): Uri.Path = Uri.Path.empty / args.repoId.id / args.id

      val implMap: Map[Vector[Uri.Path.Segment], LocalRpcImpl[F, _, _, HttpPost]] =
        routes.impls.iterator.map(impl => path(impl.rpc.serializable.args).segments -> impl).toMap

      def run[A, B](impl: LocalSerializableRpcImpl[F, A, B, HttpPost], request: Request[F]): F[Response[F]] = {
        implicit val decoder: EntityDecoder[F, A] = impl.rpc.args.aCodec.decoder
        implicit val encoder: EntityEncoder[F, B] = impl.rpc.args.bCodec.encoder
        for {
          a <- request.as[A]
          b <- impl.run(a)
        } yield
          Response(Status.Ok).withEntity(b)
      }

      HttpRoutes[F] { request =>
        for {
          _ <- OptionT.when(request.method == Method.POST)(())
          impl <- OptionT.fromOption[F](implMap.get(request.pathInfo.segments))
          response <- OptionT.liftF(run(impl.serializable, request))
        } yield
          response
      }
    }
  }
}
