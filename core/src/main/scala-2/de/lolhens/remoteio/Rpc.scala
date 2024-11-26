package de.lolhens.remoteio

import cats.Functor
import cats.syntax.functor._
import de.lolhens.remoteio.Rpc.{LocalRpcImpl, Protocol, RemoteRpcImpl, RpcRoutes, SerializableRpc}

sealed trait Rpc[F[_], A, B, P <: Protocol[P]] {
  val serializable: SerializableRpc[F, _, _, P]

  def apply(a: A)(implicit impl: RemoteRpcImpl[F, P]): F[B]

  def impl(f: A => F[B]): LocalRpcImpl[F, A, B, P]

  private[Rpc] var _implCache: (RpcRoutes[F, P], LocalRpcImpl[F, A, B, P]) = null

  protected val eqObj: Any

  override def equals(obj: Any): Boolean = obj match {
    case rpc: Rpc[F, A, B, P]@unchecked => rpc.eqObj == eqObj
    case _ => false
  }

  override def hashCode(): Int = eqObj.hashCode()
}

object Rpc {
  sealed abstract case class SerializableRpc[F[_], A, B, P <: Protocol[P]] private[Rpc](protocol: P,
                                                                                        args: P#Args[F, A, B]) extends Rpc[F, A, B, P] {
    override val serializable: SerializableRpc[F, _, _, P] = this

    def apply(a: A)(implicit impl: RemoteRpcImpl[F, P]): F[B] = impl.run(this, a)

    def impl(f: A => F[B]): LocalSerializableRpcImpl[F, A, B, P] = new LocalSerializableRpcImpl[F, A, B, P](this, f) {}

    override protected val eqObj = (protocol, args)
  }

  final class RpcPartiallyApplied2[F[_], A, B, P <: Protocol[P]] private[Rpc](val protocol: Protocol[P]) extends AnyVal {
    def apply(args: P#Args[F, A, B]): Rpc[F, A, B, P] =
      new SerializableRpc[F, A, B, P](protocol.asInstanceOf[P], args) {}

    def apply()
             (implicit args: P#Args[F, A, B], dummyImplicit: DummyImplicit): Rpc[F, A, B, P] =
      new SerializableRpc[F, A, B, P](protocol.asInstanceOf[P], args) {}
  }

  final class RpcPartiallyApplied[F[_], A, B] private[Rpc](val dummy: Unit) extends AnyVal {
    def apply[P <: Protocol[P]](protocol: Protocol[P]): RpcPartiallyApplied2[F, A, B, P] =
      new RpcPartiallyApplied2[F, A, B, P](protocol)
  }

  def apply[F[_], A, B]: RpcPartiallyApplied[F, A, B] = new RpcPartiallyApplied[F, A, B](())

  type BiinvariantRpc[F[_], P <: Protocol[P], G[_[_], _, _, P2 <: Protocol[P2]]] = Biinvariant[({type L[A, B] = Rpc[F, A, B, P]})#L]

  implicit def biinvariant[F[_] : Functor, P <: Protocol[P]]: BiinvariantRpc[F, P, Rpc] = {
    type RpcFP[A, B] = Rpc[F, A, B, P]
    new Biinvariant[RpcFP] {
      override def biimap[A, B, C, D](fab: RpcFP[A, B])(fa: A => C)(ga: C => A)(fb: B => D)(gb: D => B): RpcFP[C, D] = new Rpc[F, C, D, P] {
        self =>
        override val serializable: SerializableRpc[F, _, _, P] = fab.serializable

        override def apply(a: C)(implicit impl: RemoteRpcImpl[F, P]): F[D] = fab.apply(ga(a)).map(fb)

        override def impl(f: C => F[D]): LocalRpcImpl[F, C, D, P] = {
          val localRpcImpl = fab.impl(a => f(fa(a)).map(gb))
          new LocalRpcImpl[F, C, D, P] {
            override def serializable: LocalSerializableRpcImpl[F, _, _, P] = localRpcImpl.serializable

            override def rpc: Rpc[F, C, D, P] = self

            override def run: C => F[D] = f
          }
        }

        override protected val eqObj = (serializable, fa, ga, fb, gb)
      }
    }
  }

  trait Protocol[P <: Protocol[P]] {
    type Args[F[_], A, B]
  }

  trait RemoteRpcImpl[F[_], P <: Protocol[P]] {
    def run[A, B, Args](rpc: SerializableRpc[F, A, B, P], a: A): F[B]
  }

  trait LocalRpcImpl[F[_], A, B, P <: Protocol[P]] {
    def serializable: LocalSerializableRpcImpl[F, _, _, P]

    def rpc: Rpc[F, A, B, P]

    def run: A => F[B]
  }

  sealed abstract case class LocalSerializableRpcImpl[F[_], A, B, P <: Protocol[P]] private[Rpc](rpc: SerializableRpc[F, A, B, P],
                                                                                                 run: A => F[B]) extends LocalRpcImpl[F, A, B, P] {
    override def serializable: LocalSerializableRpcImpl[F, _, _, P] = this
  }

  final case class RpcRoutes[F[_], P <: Protocol[P]](impls: LocalRpcImpl[F, _, _, P]*) {
    impls.groupBy(_.rpc.serializable).foreach {
      case (rpc, impls) =>
        if (impls.size > 1)
          throw new IllegalArgumentException(s"rpc must be unique: $rpc")
    }

    protected val implMap: Map[Rpc[F, _, _, P], LocalRpcImpl[F, _, _, P]] =
      impls.iterator.map(_.serializable).map(impl => impl.rpc -> impl).toMap

    def apply[A, B](rpc: SerializableRpc[F, A, B, P]): LocalRpcImpl[F, A, B, P] = {
      val implCache = rpc._implCache
      if (implCache != null && (implCache._1 eq this)) {
        implCache._2
      } else {
        val impl = implMap(rpc).asInstanceOf[LocalRpcImpl[F, A, B, P]]
        rpc._implCache = (this, impl)
        impl
      }
    }

    lazy val localImpl: RemoteRpcImpl[F, P] = new RemoteRpcImpl[F, P] {
      override def run[A, B, Args](rpc: SerializableRpc[F, A, B, P], a: A): F[B] = apply(rpc).run(a)
    }
  }
}
