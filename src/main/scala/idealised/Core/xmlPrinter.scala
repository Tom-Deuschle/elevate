package idealised.Core

import java.io.{File, PrintWriter}

import scala.xml._

object ToString {
  def apply(a: Any): String = {
    if (a == null) { "null" } else { a.toString }
  }
}

object xmlPrinter {

  def toFile[T <: PhraseType](filename: String, p: Phrase[T]): Unit = {
    val pw = new PrintWriter(new File(filename))
    try pw.write(asString(p)) finally pw.close()
  }

  def asString[T <: PhraseType](p: Phrase[T]): String = {
    <expression p={PrettyPrinter(p)}>{apply(p)}</expression>.toString()
  }

  def apply[T <: PhraseType](p: Phrase[T]): xml.Elem = {
    val elem = p match {
      case app: ApplyPhrase[a, T] =>
        <apply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type={ToString(app.arg.t)}>{apply(app.arg)}</arg>
        </apply>

      case app: NatDependentApplyPhrase[T] =>
        <natApply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type="Nat">{app.arg}</arg>
        </natApply>

      case app: TypeDependentApplyPhrase[T] =>
        <typeApply>
          <fun type={ToString(app.fun.t)}>{apply(app.fun)}</fun>
          <arg type="Nat">{app.arg}</arg>
        </typeApply>

      case p1: Proj1Phrase[a, b] =>
        <π1>{apply(p1.pair)}</π1>

      case p2: Proj2Phrase[a, b] =>
        <π2>{apply(p2.pair)}</π2>

      case IfThenElsePhrase(cond, thenP, elseP) =>
        <ifThenElse>
          <if type={ToString(cond.t)}>{apply(cond)}</if>
          <then type={ToString(thenP.t)}>{apply(thenP)}</then>
          <else type={ToString(elseP.t)}>{apply(elseP)}</else>
        </ifThenElse>

      case UnaryOpPhrase(op, x) =>
        <unary op={op.toString}>{apply(x)}</unary>

      case BinOpPhrase(op, lhs, rhs) =>
        <binary op={op.toString}>
          <lhs type={ToString(lhs.t)}>{apply(lhs)}</lhs>
          <rhs type={ToString(rhs.t)}>{apply(rhs)}</rhs>
        </binary>

      case IdentPhrase(name, _) =>
        <identifier name={name} />

      case LambdaPhrase(param, body) =>
        <λ param={param.name}>
          {apply(body)}
        </λ>

      case NatDependentLambdaPhrase(param, body) =>
        <Λ param={param.name + " : nat"}>
          {apply(body)}
        </Λ>

      case TypeDependentLambdaPhrase(param, body) =>
        <Λ param={param.name + " : dt"}>
          {apply(body)}
        </Λ>

      case LiteralPhrase(d, _) => <lit>{d}</lit>

      case PairPhrase(fst, snd) =>
        <pair>
          <fst type={ToString(fst.t)}>{apply(fst)}</fst>
          <snd type={ToString(snd.t)}>{apply(snd)}</snd>
        </pair>

      case c: Combinator[_] => c.xmlPrinter

    }
    elem.copy(attributes =
      append(elem.attributes,
        Attribute("type", Text(ToString(p.t)), Null)))
  }

  def append(head: MetaData, node: MetaData): MetaData = {
    head match {
      case Null => node
      case _ => head.next match {
        case Null => head.copy(next = node)
        case _ => head.copy(next = append(head.next, node))
      }
    }
  }

}