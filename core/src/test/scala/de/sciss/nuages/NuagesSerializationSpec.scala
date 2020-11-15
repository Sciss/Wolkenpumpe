package de.sciss.nuages

import de.sciss.lucre.store.BerkeleyDB
import de.sciss.lucre.{Folder, Obj}
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.proc.Implicits._
import de.sciss.proc.{Durable, Proc}
import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers

/*
 To test only this suite:

 test-only de.sciss.nuages.NuagesSerializationSpec

 */
class NuagesSerializationSpec extends FixtureAnyFlatSpec with Matchers {
  type S = Durable
  type T = Durable.Txn
  type FixtureParam = Durable

  Wolkenpumpe.init()

  final def withFixture(test: OneArgTest): Outcome = {
    val system = Durable(BerkeleyDB.tmp())
    try {
      test(system)
    }
    finally {
      system.close()
    }
  }

  "Nuages" should "serialize and deserialize" in { system =>
    val nH = system.step { implicit tx =>
      val n = Nuages.timeline[T]
      val Surface.Timeline(t) = n.surface
      val p = Proc[T]()
      p.name = "Schoko"
      assert(p.name === "Schoko")
      t.modifiableOption.get.add(Span(0L, 10000L), p)
      n.name = "Britzel"
      tx.newHandle(n)
    }

    val oH = system.step { implicit tx =>
      val n = nH()  // uses direct serializer
      val Surface.Timeline(t) = n.surface
      val objects = t.intersect(0L).toList.flatMap(_._2.map(_.value))
      assert(objects.map(_.name) === List("Schoko"))
      tx.newHandle(n: Obj[T])
    }

    system.step { implicit tx =>
      val o = oH()  // uses Obj serializer
      assert(o.name === "Britzel")
    }

    val fH = system.step { implicit tx =>
      val n = nH()
      val f = Folder[T]()
      f.addLast(n)
      tx.newHandle(f)
    }

    system.step { implicit tx =>
      val f = fH()
      val o = f.last    // this was revealing a de-serialization bug in Timeline
      assert(o.isInstanceOf[Nuages[T]])
      val n = o.asInstanceOf[Nuages[T]]
      val Surface.Timeline(t) = n.surface
      val objects = t.intersect(0L).toList.flatMap(_._2.map(_.value))
      assert(objects.map(_.name) === List("Schoko"))
    }
  }
}
