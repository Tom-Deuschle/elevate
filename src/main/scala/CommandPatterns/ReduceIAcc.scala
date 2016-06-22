package CommandPatterns

import Core._
import Core.PhraseType._
import Core.OperationalSemantics._
import ExpPatterns.Idx
import DSL._
import Compiling.SubstituteImplementations
import apart.arithmetic.ArithExpr

case class ReduceIAcc(n: ArithExpr,
                      dt1: DataType,
                      dt2: DataType,
                      out: Phrase[AccType],
                      f: Phrase[AccType -> (ExpType -> (ExpType -> CommandType))],
                      init: Phrase[ExpType],
                      in: Phrase[ExpType]) extends IntermediateCommandPattern {

  override def typeCheck(): CommandType = {
    import TypeChecker._
    (TypeChecker(out), TypeChecker(init), TypeChecker(in)) match {
      case (AccType(dt2_), ExpType(dt3), ExpType(ArrayType(n_, dt1_)))
        if dt1_ == dt1 && dt2_ == dt2 && dt2 == dt3 && n_ == n =>

        setParamType(f, AccType(dt2))
        setSecondParamType(f, ExpType(dt1))
        setThirdParamType(f, ExpType(dt2))
        TypeChecker(f) match {
          case FunctionType(AccType(t1), FunctionType(ExpType(t2), FunctionType(ExpType(t3), CommandType()))) =>
            if (dt2 == t1 && dt1 == t2 && dt2 == t3) CommandType()
            else {
              error(dt2.toString + ", " + t1.toString + " as well as " +
                dt1.toString + ", " + t2.toString + " and " + dt2.toString + ", " + t3.toString,
                expected = "them to match")
            }
          case x => error(x.toString, "FunctionType")
        }
      case x => error(x.toString, "(AccType, ExpType, ArrayType)")
    }
  }

  override def visitAndRebuild(fun: VisitAndRebuild.fun): Phrase[CommandType] = {
    ReduceIAcc(n, dt1, dt2,
      VisitAndRebuild(out, fun),
      VisitAndRebuild(f, fun),
      VisitAndRebuild(init, fun),
      VisitAndRebuild(in, fun))
  }

  override def eval(s: Store): Store = {
    val fE = OperationalSemantics.eval(s, f)(TrinaryFunctionEvaluator)
    val n = TypeChecker(in) match { case ExpType(ArrayType(len, _)) => len }

    (0 until n.eval).foldLeft(s)( (sOld, i) => {
      val comm = fE(out)(Idx(in, LiteralPhrase(i)))(init)
      OperationalSemantics.eval(sOld, comm)
    } )
  }

  override def prettyPrint: String = s"reduceIAcc ${PrettyPrinter(out)} ${PrettyPrinter(f)} ${PrettyPrinter(init)} ${PrettyPrinter(in)}"

  override def substituteImpl: Phrase[CommandType] = {
    `new`(init.t.dataType, PrivateMemory, accum => {
      (accum.wr `:=` init) `;`
      `for`(n, i => {
        SubstituteImplementations( f(accum.wr)(in `@` i)(accum.rd) )
      }) `;`
      (out `:=` accum.rd)
    } )
  }
}
