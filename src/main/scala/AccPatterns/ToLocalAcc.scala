package AccPatterns

import Core.OperationalSemantics._
import Core._
import apart.arithmetic.ArithExpr
import opencl.generator.OpenCLAST.VarRef

import scala.xml.Elem

case class ToLocalAcc(dt: DataType,
                      p: Phrase[AccType]) extends AccPattern{

  override def typeCheck(): AccType = {
    import TypeChecker._
    p.t =?= acc"[$dt]"
    acc"[$dt]"
//    TypeChecker(p) match {
//      case AccType(dt) => AccType(dt)
//      case x => error(x.toString, "AccType")
//    }
  }

  override def eval(s: Store): AccIdentifier = ???

  override def toOpenCL(env: ToOpenCL.Environment): VarRef = ???

  def toOpenCL(env: ToOpenCL.Environment,
               arrayAccess: List[(ArithExpr, ArithExpr)],
               tupleAccess: List[ArithExpr], dt: DataType): VarRef = ???

  override def visitAndRebuild(fun: VisitAndRebuild.fun): Phrase[AccType] = {
    ToLocalAcc(fun(dt), VisitAndRebuild(p, fun))
  }

  override def prettyPrint: String = s"(toLocalAcc ${PrettyPrinter(p)})"

  override def xmlPrinter: Elem =
    <toLocalAcc>
      {Core.xmlPrinter(p)}
    </toLocalAcc>
}
