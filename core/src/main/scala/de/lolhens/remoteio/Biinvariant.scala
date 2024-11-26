package de.lolhens.remoteio

trait Biinvariant[F[_, _]] {
  def biimap[A, B, C, D](fab: F[A, B])(fa: A => C)(ga: C => A)(fb: B => D)(gb: D => B): F[C, D]
}

object Biinvariant {
  object syntax {
    implicit class BiinvariantSyntax[F[_, _], A, B](val self: F[A, B]) extends AnyVal {
      def biimap[C, D](fa: A => C)(ga: C => A)(fb: B => D)(gb: D => B)(implicit biinvariant: Biinvariant[F]): F[C, D] =
        biinvariant.biimap(self)(fa)(ga)(fb)(gb)
    }
  }
}
