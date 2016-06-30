package ExpPatterns

import CommandPatterns.{ReduceIAcc, ReduceIExp}
import Core.PhraseType._
import Core._
import apart.arithmetic.ArithExpr

case class ReduceSeq(n: ArithExpr,
                     dt1: DataType, dt2: DataType,
                     f: Phrase[ExpType -> (ExpType -> ExpType)],
                     init: Phrase[ExpType],
                     array: Phrase[ExpType])
  extends AbstractReduce(n, dt1, dt2, f, init, array, ReduceSeq, ReduceIAcc, ReduceIExp)