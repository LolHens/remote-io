package de.lolhens.remoteio

import cats.effect.IO
import de.lolhens.remoteio.HttpPost.{HttpPostRpcImpl, HttpPostRpcRepo}
import de.lolhens.remoteio.Rpc.{RemoteRpcImpl, RpcRoutes}
import org.http4s.Uri
import org.http4s.client.Client
import Biinvariant.syntax._

import java.nio.charset.StandardCharsets

class HttpPostTest extends CatsEffectSuite {
  private def s2b(string: String): Array[Byte] = string.getBytes(StandardCharsets.UTF_8)
  private def b2s(bytes: Array[Byte]): String = new String(bytes, StandardCharsets.UTF_8)

  object TestRepo extends HttpPostRpcRepo("test") {
    val rpc1: Rpc[IO, String, String, HttpPost] = Rpc[IO, String, String](HttpPost)()
    //val rpc2: Rpc[IO, String, String, HttpPost] = Rpc[IO, String, String](HttpPost)()
    val rpc2Bin: Rpc[IO, Array[Byte], Array[Byte], HttpPost] = Rpc.biinvariant[IO, HttpPost].biimap(Rpc[IO, String, String](HttpPost)())(s2b)(b2s)(s2b)(b2s)
  }

  val rpcRoutes = RpcRoutes(
    TestRepo.rpc1.impl { string =>
      IO.pure("rpc1: " + string)
    },
    TestRepo.rpc2Bin.impl { string =>
      IO.pure(s2b("rpc2: " + b2s(string)))
    }
  )

  test("server and client") {
    implicit val remoteRpcImpl: RemoteRpcImpl[IO, HttpPost] = HttpPostRpcImpl[IO](
      Client.fromHttpApp(rpcRoutes.toRoutes.orNotFound),
      Uri.unsafeFromString("http://localhost:8080")
    )

    TestRepo.rpc1("a").map { e => assertEquals(e, "rpc1: a") } >>
      //TestRepo.rpc2("b").map { e => assertEquals(e, "rpc2: b") } >>
      TestRepo.rpc2Bin(s2b("b-bin")).map { e => assertEquals(b2s(e), "rpc2: b-bin")}
  }

  test("local impl") {
    implicit val remoteRpcImpl: RemoteRpcImpl[IO, HttpPost] = rpcRoutes.localImpl

    TestRepo.rpc1("a").map { e => assertEquals(e, "rpc1: a") } >>
      //TestRepo.rpc2("b").map { e => assertEquals(e, "rpc2: b") } >>
      TestRepo.rpc2Bin(s2b("b-bin")).map { e => assertEquals(b2s(e), "rpc2: b-bin")}
  }
}
