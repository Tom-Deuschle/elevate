package elevate.rise.strategies

import elevate.core.{RewriteResult, Strategy}
import elevate.core.strategies.basic._
import elevate.rise.Rise
import elevate.rise.rules.algorithmic._
import elevate.rise.rules.movement._
import elevate.rise.rules.traversal.{argument, function}
import elevate.rise.strategies.traversal._
import elevate.rise.strategies.normalForm._

object tiling {

  def tileND: Int => Int => Strategy[Rise] = d => n => tileNDList(List.tabulate(d)(_ => n))

  // special syntax for 2D case - for ICFP'20 paper
  def tile(x: Int, y: Int): Strategy[Rise] = tileNDList(List(x,y))

  def tileNDList: List[Int] => Strategy[Rise] =

    n => n.size match {
        case x if x <= 0 => id()
        // ((map f) arg)
        case 1 => function(splitJoin(n.head))      // loop-blocking
        case i => fmap(tileNDList(n.tail)) `;`     // recurse
                  function(splitJoin(n.head)) `;`  // loop-blocking
                  interchange(i)                      // loop-interchange
      }


  // Notation: A.a -> a == tile dimension; A == original dimension
  // a.b.c.d: 4D array (outer => inner): a == outermost dim; d == innermost dim
  //
  // dim == 2 -> shift one level:
  //    A.a.B.b => A.B.a.b
  //    achieved by: (****f => *T o ****f o *T)
  //
  // dim == 3 -> shift two levels
  //    A.a.B.C.b.c => A.B.C.a.b.c
  //    (******f => *T o **T o ******f o **T o *T)
  // dim == 4 -> shift three levels ...
  def interchange: Int => Strategy[Rise] =
    d => {
      val joins = d
      val transposes = (1 to d-2).sum
      RNF `;` shiftDimRec(joins + transposes)(d-1)
    }

  // position: how far to move right until we reach maps
  // level:    how deep transpose pairs are nested in maps
  def shiftDimRec: Int => Int => Strategy[Rise] =
    position => level => DFNF `;`
      (level match {
      case 1 => moveTowardsArgument(position)(loopInterchangeAtLevel(1))
      case l => shiftDimRec(position)(l - 1) `;` RNF `;`
        moveTowardsArgument(position + l - 1)(loopInterchangeAtLevel(l))
    })

  // in front of **f, creating transpose pairs, move one transpose over **f
  def loopInterchange: Strategy[Rise] =
      idAfter `;` createTransposePair `;` DFNF `;` argument(mapMapFBeforeTranspose)

  // level == 0: A.B.C.D => A.B.D.C
  //             ^ ^        ^ ^
  // level == 1: A.B.C.D => A.C.B.D
  //               ^ ^        ^ ^   ... and so on
  def loopInterchangeAtLevel: Int => Strategy[Rise] =
    level => applyNTimes(level)((e: Strategy[Rise]) => fmap(e))(loopInterchange) `;` RNF
}
