package idealised.C.CodeGeneration

import idealised.DPIA.DSL._
import idealised.DPIA.FunctionalPrimitives._
import idealised.DPIA.ImperativePrimitives._
import idealised.DPIA.Phrases._
import idealised.DPIA.Semantics.OperationalSemantics
import idealised.DPIA.Semantics.OperationalSemantics._
import idealised.DPIA.Types._
import idealised.DPIA._
import idealised.SurfaceLanguage.Operators
import idealised._
import lift.arithmetic.{NamedVar, _}

import scala.collection.{immutable, mutable}
import scala.language.implicitConversions

object CodeGenerator {

  final case class Environment(identEnv: immutable.Map[Identifier[_ <: BasePhraseTypes], C.AST.DeclRef],
                               commEnv: immutable.Map[Identifier[CommandType], C.AST.Stmt]) {
    def updatedIdentEnv(kv: (Identifier[_ <: BasePhraseTypes], C.AST.DeclRef)): Environment = {
      this.copy(identEnv = identEnv + kv)
    }

    def updatedCommEnv(kv: (Identifier[CommandType], C.AST.Stmt)): Environment = {
      this.copy(commEnv = commEnv + kv)
    }
  }

  type Path = immutable.List[Nat]

  type Declarations = mutable.ListBuffer[C.AST.Decl]
  type Ranges = immutable.Map[String, lift.arithmetic.Range]

  def apply(): CodeGenerator =
    new CodeGenerator(mutable.ListBuffer[C.AST.Decl](), immutable.Map[String, lift.arithmetic.Range]())
}

class CodeGenerator(val decls: CodeGenerator.Declarations,
                    val ranges: CodeGenerator.Ranges)
  extends DPIA.Compilation.CodeGenerator[CodeGenerator.Environment, CodeGenerator.Path, C.AST.Stmt, C.AST.Expr, C.AST.Decl, C.AST.DeclRef, C.AST.Type] {
  type Environment = CodeGenerator.Environment
  type Path = CodeGenerator.Path
  type Stmt = C.AST.Stmt
  type Decl = C.AST.Decl
  type Expr = C.AST.Expr
  type Ident = C.AST.DeclRef
  type Type = C.AST.Type

  override def name: String = "C"

  def addDeclaration(decl: Decl): Unit = {
    if (decls.exists(_.name == decl.name)) {
      println(s"warning: declaration with name ${decl.name} already defined")
    } else {
      decls += decl
    }
  }

  def updatedRanges(key: String, value: lift.arithmetic.Range): CodeGenerator =
    new CodeGenerator(decls, ranges.updated(key, value))

  override def generate(phrase: Phrase[CommandType], env: CodeGenerator.Environment): (scala.Seq[Decl], Stmt) = {
    val stmt = cmd(phrase, env)
    (decls, stmt)
  }

  override def cmd(phrase: Phrase[CommandType], env: Environment): Stmt = {
    phrase match {
      case Phrases.IfThenElse(cond, thenP, elseP) =>
        C.AST.IfThenElse(exp(cond, env, Nil), cmd(thenP, env), Some(cmd(elseP, env)))

      case i: Identifier[CommandType] => env.commEnv(i)

      case Skip() => C.AST.Comment("skip")

      case Seq(p1, p2) => C.AST.Stmts(cmd(p1, env), cmd(p2, env))

      case Assign(_, a, e) => C.AST.Assignment(acc(a, env, Nil), exp(e, env, Nil))

      case New(dt, _, Lambda(v, p)) => CCodeGen.codeGenNew(dt, v, p, env)

      case NewDoubleBuffer(_, _, dt, n, in, out, Lambda(ps, p)) =>
        CCodeGen.codeGenNewDoubleBuffer(ArrayType(n, dt), in, out, ps, p, env)

      case NewRegRot(n, dt, Lambda(registers, Lambda(rotate, body))) =>
        CCodeGen.codeGenNewRegRot(n, dt, registers, rotate, body, env)

      case For(n, Lambda(i, p)) => CCodeGen.codeGenFor(n, i, p, env)

      case ForNat(n, NatDependentLambda(i, p)) => CCodeGen.codeGenForNat(n, i, p, env)

      case Proj1(pair) => cmd(Lifting.liftPair(pair)._1, env)
      case Proj2(pair) => cmd(Lifting.liftPair(pair)._2, env)

      case Apply(_, _) | NatDependentApply(_, _) | TypeDependentApply(_, _) |
           _: CommandPrimitive =>
        error(s"Don't know how to generate code for $phrase")
    }
  }

  override def acc(phrase: Phrase[AccType], env: Environment, path: Path): Expr = {
    phrase match {
      case i@Identifier(_, AccType(dt)) => CCodeGen.generateAccess(dt,
        env.identEnv.applyOrElse(i, (_: Phrase[_]) => {
          throw new Exception(s"Expected to find `$i' in the environment: `${env.identEnv}'")
        }), path, env)

      case SplitAcc(_, m, _, a) => path match {
        case i :: ps => acc(a, env, i / m :: i % m :: ps)
        case Nil => error(s"Expected path to be not empty")
      }
      case JoinAcc(_, m, _, a) => path match {
        case i :: j :: ps => acc(a, env, i * m + j :: ps)
        case _ :: Nil | Nil => error(s"Expected path to contain at least two elements")
      }

      case RecordAcc1(_, _, a) => acc(a, env, Cst(1) :: path)
      case RecordAcc2(_, _, a) => acc(a, env, Cst(2) :: path)

      case ZipAcc1(_, _, _, a) => path match {
        case i :: ps => acc(a, env, i :: Cst(1) :: ps)
        case Nil => error(s"Expected path to be not empty")
      }
      case ZipAcc2(_, _, _, a) => path match {
        case i :: ps => acc(a, env, i :: Cst(2) :: ps)
        case Nil => error(s"Expected path to be not empty")
      }
      case UnzipAcc(_, _, _, _) => ???

      case TakeAcc(_, _, _, a) => acc(a, env, path)
      case DropAcc(n, _, _, a) => path match {
        case i :: ps => acc(a, env, (i + n)::ps)
        case Nil => error(s"Expected path to be not empty")
      }

      case CycleAcc(_, m, _, a) => path match {
        case i :: ps => acc(a, env, i % m :: ps)
        case _ => error(s"Expected path to be not empty")
      }

      case ScatterAcc(_, _, idxF, a) => path match {
        case i :: ps => acc(a, env, OperationalSemantics.evalIndexExp(idxF(i)) :: ps)
        case Nil => error(s"Expected path to be not empty")
      }

      case MapAcc(n, dt, _, f, a) => path match {
        case i :: ps =>  acc( f( IdxAcc(n, dt, Literal(IndexData(i, IndexType(n))), a) ), env, ps )
        case Nil => error(s"Expected path to be not empty")
      }

      case IdxAcc(_, _, i, a) => CCodeGen.codeGenIdxAcc(i, a, env, path)

      case DepIdxAcc(_, _, _, i, a) => acc(a, env, i :: path)

      case IdxVecAcc(_, _, i, a) => CCodeGen.codeGenIdxVecAcc(i, a, env, path)

      case Proj1(pair) => acc(Lifting.liftPair(pair)._1, env, path)
      case Proj2(pair) => acc(Lifting.liftPair(pair)._2, env, path)

      case Apply(_, _) | NatDependentApply(_, _) | TypeDependentApply(_, _) |
           Phrases.IfThenElse(_, _, _) | _: AccPrimitive =>
        error(s"Don't know how to generate code for $phrase")
    }
  }

  override def exp(phrase: Phrase[ExpType], env: Environment, path: Path): Expr = {
    phrase match {
      case i@Identifier(_, ExpType(dt)) => CCodeGen.generateAccess(dt,
        env.identEnv.applyOrElse(i, (_: Phrase[_]) => {
          throw new Exception(s"Expected to find `$i' in the environment: `${env.identEnv}'")
        }), path, env)

      case Phrases.Literal(n) => (path, n.dataType) match {
        case (Nil, _: IndexType) => CCodeGen.codeGenLiteral(n)
        case (Nil, _: ScalarType) => CCodeGen.codeGenLiteral(n)
        // case (_ :: _ :: Nil, _: ArrayType) => C.AST.Literal("0.0f") // TODO: (used in gemm like this) !!!!!!!
        case (i :: Nil, _: ArrayType) => C.AST.ArraySubscript(CCodeGen.codeGenLiteral(n), C.AST.ArithmeticExpr(i))
        case _ => error(s"Unexpected: $n $path")
      }

      case UnaryOp(op, e) => phrase.t.dataType match {
        case _: ScalarType => path match {
          case Nil => CCodeGen.codeGenUnaryOp(op, exp(e, env, Nil))
          case _ => error(s"Expected path to be empty")
        }
        case _ => error(s"Expected scalar types")
      }

      case BinOp(op, e1, e2) => phrase.t.dataType match {
        case _: ScalarType => path match {
          case Nil => CCodeGen.codeGenBinaryOp(op, exp(e1, env, Nil), exp(e2, env, Nil))
          case _ => error(s"Expected path to be empty")
        }
        case _ => error(s"Expected scalar types")
      }

      case Split(n, _, _, e) => path match {
        case i :: j :: ps => exp(e, env, i * n + j :: ps)
        case _ :: Nil | Nil => error(s"Expected path to contain at least two elements")
      }
      case Join(n, _, _, e) => path match {
        case i :: ps => exp(e, env, i / n :: i % n :: ps)
        case Nil => error(s"Expected path to be not empty")
      }

      case Zip(_, _, _, e1, e2) => path match {
        case i :: Cst(1) :: ps => exp(e1, env, i :: ps)
        case i :: Cst(2) :: ps => exp(e2, env, i :: ps)
        case _ => error(s"Expected path to have at least two values and contain " +
          s"1 or 2 as second value.")
      }
      case Unzip(_, _, _, _) => ???

      case Record(_, _, e1, e2) => path match {
        case Cst(1) :: ps => exp(e1, env, ps)
        case Cst(2) :: ps => exp(e2, env, ps)
        case _ => error(s"Expected path to have at least two values and contain " +
          s"1 or 2 as second value.")
      }
      case Fst(_, _, e) => exp(e, env, Cst(1) :: path)
      case Snd(_, _, e) => exp(e, env, Cst(2) :: path)

      case Take(_, _, _, e) => exp(e, env, path)

      case Drop(n, _, _, e) => path match {
          case i :: ps => exp(e, env, (i + n)::ps)
          case Nil => error(s"Expected path to be not empty")
        }

      case Cycle(_, m, _, e) => path match {
        case i :: ps => exp(e, env, i % m :: ps)
        case _ => error(s"Expected path to be not empty")
      }

      case Gather(_, _, idxF, a) => path match {
        case i :: ps => exp(a, env, OperationalSemantics.evalIndexExp(idxF(i)) :: ps)
        case Nil => error(s"Expected path to be not empty")
      }

      case Slide(_, _, s2, _, e) => path match {
        case i :: j :: ps => exp(e, env, i * s2 + j :: ps)
        case _ :: Nil | Nil => error(s"Expected path to contain at least two elements")
      }

      // TODO: this has to be refactored
      case VectorFromScalar(n, st, e) => path match {
        case _ :: ps =>
          // in this case we index straight into the vector build from a single scalar
          // it is equivalent to return the scalar `e' without boxing and unboxing it
          exp(e, env, ps)
        //          C.AST.ArraySubscript(
        //            C.AST.Literal( "(" + s"($st[$n]){" + C.AST.Printer(exp(e, env, ps)) + "})" ),
        //            C.AST.ArithmeticExpr(i))

        case Nil =>
          C.AST.Literal("(" + s"($st[$n]){" + C.AST.Printer(exp(e, env, Nil)) + "})")
      }

      case Map(n, dt, _, f, e) => path match {
        case i :: ps =>  exp( f( Idx(n, dt, Literal(IndexData(i, IndexType(n))), e) ), env, ps )
        case Nil => error(s"Expected path to be not empty")
      }

      case Idx(_, _, i, e) => CCodeGen.codeGenIdx(i, e, env, path)

      case DepIdx(_, _, _, i, e) => exp(e, env, i :: path)

      case IdxVec(_, _, i, e) => CCodeGen.codeGenIdxVec(i, e, env, path)

      case ForeignFunction(f, inTs, outT, args) => CCodeGen.codeGenForeignFunction(f, inTs, outT, args, env, path)

      case Proj1(pair) => exp(Lifting.liftPair(pair)._1, env, path)
      case Proj2(pair) => exp(Lifting.liftPair(pair)._2, env, path)

      case Apply(_, _) | NatDependentApply(_, _) | TypeDependentApply(_, _) |
           Phrases.IfThenElse(_, _, _) | _: ExpPrimitive =>
        error(s"Don't know how to generate code for $phrase")
    }
  }

  override def typ(dt: DataType): Type = {
    dt match {
      case b: idealised.DPIA.Types.BasicType => b match {
        case idealised.DPIA.Types.bool => C.AST.Type.int
        case idealised.DPIA.Types.int => C.AST.Type.int
        case idealised.DPIA.Types.float => C.AST.Type.float
        case idealised.DPIA.Types.double => C.AST.Type.double
        case _: idealised.DPIA.Types.IndexType => C.AST.Type.int
      }
      case a: idealised.DPIA.Types.ArrayType => C.AST.ArrayType(typ(a.elemType), Some(a.size))
      case a: idealised.DPIA.Types.DepArrayType => C.AST.ArrayType(typ(a.elemType), Some(a.size)) // TODO: be more precise with the size?
      case r: idealised.DPIA.Types.RecordType =>
        C.AST.StructType(r.fst.toString + "_" + r.snd.toString, immutable.Seq(
          (typ(r.fst), "fst"),
          (typ(r.snd), "snd")))
      case _: idealised.DPIA.Types.DataTypeIdentifier => throw new Exception("This should not happen")
    }
  }

  protected object CCodeGen {
    def codeGenNew(dt: DataType,
                   v: Identifier[VarType],
                   p: Phrase[CommandType],
                   env: Environment): Stmt = {
      val ve = Identifier(s"${v.name}_e", v.t.t1)
      val va = Identifier(s"${v.name}_a", v.t.t2)
      val vC = C.AST.DeclRef(v.name)

      C.AST.Block(immutable.Seq(
        C.AST.DeclStmt(C.AST.VarDecl(vC.name, typ(dt))),
        cmd(Phrase.substitute(Pair(ve, va), `for` = v, `in` = p),
          env updatedIdentEnv (ve -> vC)
            updatedIdentEnv (va -> vC))))
    }

    def codeGenNewDoubleBuffer(dt: ArrayType,
                               in: Phrase[ExpType],
                               out: Phrase[AccType],
                               ps: Identifier[VarType x CommandType x CommandType],
                               p: Phrase[CommandType],
                               env: Environment): Stmt = {
      import C.AST._
      import BinaryOperator._
      import UnaryOperator._

      val ve = Identifier(s"${ps.name}_e", ps.t.t1.t1.t1)
      val va = Identifier(s"${ps.name}_a", ps.t.t1.t1.t2)
      val done = Identifier(s"${ps.name}_swap", ps.t.t1.t2)
      val swap = Identifier(s"${ps.name}_done", ps.t.t2)

      val tmp1 = DeclRef(freshName("tmp1_"))
      val tmp2 = DeclRef(freshName("tmp2_"))
      val in_ptr = DeclRef(freshName("in_ptr_"))
      val out_ptr = DeclRef(freshName("out_ptr_"))
      val flag = DeclRef(freshName("flag_"))

      Block(immutable.Seq(
        // create variables: `tmp1', `tmp2`, `in_ptr', and `out_ptr'
        DeclStmt(VarDecl(tmp1.name, typ(dt))),
        DeclStmt(VarDecl(tmp2.name, typ(dt))),
        makePointerDecl(in_ptr.name, dt.elemType, UnaryExpr(&, exp(in, env, List(0)))),
        makePointerDecl(out_ptr.name, dt.elemType, tmp1),
        // create boolean flag used for swapping
        DeclStmt(VarDecl(flag.name, Type.uchar, Some(Literal("1")))),
        // generate body
        cmd(
          Phrase.substitute(Pair(Pair(Pair(ve, va), swap), done), `for` = ps, `in` = p),
          env updatedIdentEnv (ve -> in_ptr) updatedIdentEnv (va -> out_ptr)
            updatedCommEnv (swap -> {
            Block(immutable.Seq(
              Assignment(in_ptr, TernaryExpr(flag, tmp1, tmp2)),
              Assignment(out_ptr, TernaryExpr(flag, tmp2, tmp1)),
              // toggle flag with xor
              Assignment(flag, BinaryExpr(flag, ^, Literal("1")))))
          })
            updatedCommEnv (done -> {
            Block(immutable.Seq(
              Assignment(in_ptr, TernaryExpr(flag, tmp1, tmp2)),
              Assignment(out_ptr, UnaryExpr(&, acc(out, env, List(0))))))
          }))
      ))
    }

    def codeGenNewRegRot(n: Nat,
                         dt: DataType,
                         registers: Identifier[VarType],
                         rotate: Identifier[CommandType],
                         body: Phrase[CommandType],
                         env: Environment): Stmt = {
      import C.AST._

      val re = Identifier(s"${registers.name}_e", registers.t.t1)
      val ra = Identifier(s"${registers.name}_a", registers.t.t2)
      val rot = Identifier(s"${rotate.name}_rotate", rotate.t)

      val registerCount = n.eval // FIXME: this is a quick solution
      // TODO: variable array
      // val rs = (0 until registerCount).map(i => DeclRef(freshName(s"r${i}_"))).toArray

      val rs = DeclRef(freshName(s"rs_"))
      val rst = DPIA.Types.ArrayType(n, dt)

      Block(
        // rs.map(r => DeclStmt(VarDecl(r.name, typ(dt))))
        Array(DeclStmt(VarDecl(rs.name, typ(rst))))
          :+ cmd(
          Phrase.substitute(immutable.Map(registers -> Pair(re, ra), rotate -> rot), `in` = body),
          env updatedIdentEnv (re -> rs) updatedIdentEnv (ra -> rs)
            updatedCommEnv (rot -> Block(
            // (1 until registerCount).map(i => Assignment(rs(i-1), rs(i)))
            (1 until registerCount).map(i => Assignment(generateAccess(rst, rs, (i - 1) :: Nil, env), generateAccess(rst, rs, i :: Nil, env)))
          ))
        )
      )
    }

    def codeGenFor(n: Nat,
                   i: Identifier[ExpType],
                   p: Phrase[CommandType],
                   env: Environment): Stmt = {
      val i_ = C.AST.DeclRef(freshName("i_"))
      val range = RangeAdd(0, n, 1)
      val updatedGen = updatedRanges(i_.name, range)

      val n_ = applySubstitutions(n, env.identEnv)

      range.numVals match {
        // iteration count is 0 => skip body; no code to be emitted
        case Cst(0) => C.AST.Comment("iteration count is 0, no loop emitted")

        // iteration count is 1 => no loop
        case Cst(1) =>
          C.AST.Stmts(C.AST.Stmts(
            C.AST.Comment("iteration count is exactly 1, no loop emitted"),
            C.AST.DeclStmt(C.AST.VarDecl(i_.name, C.AST.Type.int, init = Some(C.AST.ArithmeticExpr(0))))),
            updatedGen.cmd(p, env updatedIdentEnv (i -> i_)))

        case _ =>
          // default case
          val init = C.AST.VarDecl(i_.name, C.AST.Type.int, init = Some(C.AST.ArithmeticExpr(0)))
          val cond = C.AST.BinaryExpr(i_, C.AST.BinaryOperator.<, C.AST.ArithmeticExpr(n_))
          val increment = C.AST.Assignment(i_, C.AST.ArithmeticExpr(NamedVar(i_.name, range) + 1))

          C.AST.ForLoop(C.AST.DeclStmt(init), cond, increment,
            C.AST.Block(immutable.Seq(updatedGen.cmd(p, env updatedIdentEnv (i -> i_)))))
      }
    }

    def codeGenForNat(n: Nat,
                      i: NatIdentifier,
                      p: Phrase[CommandType],
                      env: Environment): Stmt = {
      val i_ = C.AST.DeclRef(freshName("i_"))
      val range = RangeAdd(0, n, 1)
      val updatedGen = updatedRanges(i_.name, range)

      val n_ = applySubstitutions(n, env.identEnv)

      range.numVals match {
        // iteration count is 0 => skip body; no code to be emitted
        case Cst(0) => C.AST.Comment("iteration count is 0, no loop emitted")

        // iteration count is 1 => no loop
        case Cst(1) =>
          C.AST.Stmts(C.AST.Stmts(
            C.AST.Comment("iteration count is exactly 1, no loop emitted"),
            C.AST.DeclStmt(C.AST.VarDecl(i_.name, C.AST.Type.int, init = Some(C.AST.ArithmeticExpr(0))))),
            updatedGen.cmd(p, env))

        case _ =>
          // default case
          val init = C.AST.VarDecl(i_.name, C.AST.Type.int, init = Some(C.AST.ArithmeticExpr(0)))
          val cond = C.AST.BinaryExpr(i_, C.AST.BinaryOperator.<, C.AST.ArithmeticExpr(n_))
          val increment = C.AST.Assignment(i_, C.AST.ArithmeticExpr(NamedVar(i_.name, range) + 1))

          val pSub = PhraseType.substitute(NamedVar(i_.name, range), `for` = i, in = p)

          C.AST.ForLoop(C.AST.DeclStmt(init), cond, increment,
            C.AST.Block(immutable.Seq(updatedGen.cmd(pSub, env))))
      }
    }

    def codeGenIdxAcc(i: Phrase[ExpType],
                      a: Phrase[AccType],
                      env: Environment,
                      ps: Path): Expr = {
      val idx: ArithExpr = exp(i, env, List()) match {
        case C.AST.DeclRef(name) => NamedVar(name, ranges(name))
        case C.AST.ArithmeticExpr(ae) => ae
      }

      acc(a, env, idx :: ps)
    }

    def codeGenIdxVecAcc(i: Phrase[ExpType],
                         a: Phrase[AccType],
                         env: Environment,
                         ps: Path): Expr = {
      val idx: ArithExpr = exp(i, env, List()) match {
        case C.AST.DeclRef(name) => NamedVar(name, ranges(name))
        case C.AST.ArithmeticExpr(ae) => ae
      }

      acc(a, env, idx :: ps)
    }

    def codeGenLiteral(d: OperationalSemantics.Data): Expr = {
      d match {
        case i: IndexData =>
          C.AST.ArithmeticExpr(i.n)
        case _: IntData | _: FloatData | _: BoolData =>
          C.AST.Literal(d.toString)
        case ArrayData(a) => d.dataType match {
          case ArrayType(n, st) =>
            a.head match {
              case IntData(0) | FloatData(0.0f) | BoolData(false)
                if a.distinct.length == 1 =>
                C.AST.Literal("(" + s"($st[$n]){" + a.head + "})")
              case _ =>
                C.AST.Literal("(" + s"($st[$n])" + a.mkString("{", ",", "}") + ")")
            }
          case _ => error("This should not happen")
        }
        case _ => error("Expected scalar or array types")
      }
    }

    def codeGenUnaryOp(op: Operators.Unary.Value, e: Expr): Expr = {
      C.AST.UnaryExpr(op, e)
    }

    def codeGenBinaryOp(op: Operators.Binary.Value,
                        e1: Expr,
                        e2: Expr): Expr = {
      C.AST.BinaryExpr(e1, op, e2)
    }

    def codeGenIdx(i: Phrase[ExpType],
                   e: Phrase[ExpType],
                   env: Environment,
                   ps: Path): Expr = {
      val idx: ArithExpr = exp(i, env, List()) match {
        case C.AST.DeclRef(name) => NamedVar(name, ranges(name))
        case C.AST.ArithmeticExpr(ae) => ae
      }

      exp(e, env, idx :: ps)
    }

    def codeGenIdxVec(i: Phrase[ExpType],
                      e: Phrase[ExpType],
                      env: Environment,
                      ps: Path): Expr = {
      val idx: ArithExpr = exp(i, env, List()) match {
        case C.AST.DeclRef(name) => NamedVar(name, ranges(name))
        case C.AST.ArithmeticExpr(ae) => ae
      }

      exp(e, env, idx :: ps)
    }

    def codeGenForeignFunction(funDecl: ForeignFunction.Declaration,
                               inTs: collection.Seq[DataType],
                               outT: DataType,
                               args: collection.Seq[Phrase[ExpType]],
                               env: Environment,
                               ps: Path): Expr = {
      addDeclaration(
        C.AST.FunDecl(funDecl.name,
          returnType = typ(outT),
          params = (funDecl.argNames zip inTs).map {
            case (name, dt) => C.AST.ParamDecl(name, typ(dt))
          },
          body = C.AST.Code(funDecl.body)))

      C.AST.FunCall(C.AST.DeclRef(funDecl.name), args.map(exp(_, env, ps)))
    }

    def generateAccess(dt: DataType,
                       identifier: Ident,
                       path: Path,
                       env: Environment): Expr = {
      (dt, path) match {
        case (_: BasicType, Nil) => identifier

        case (_: VectorType, i :: Nil) =>
          val data = C.AST.StructMemberAccess(identifier, C.AST.DeclRef("data"))
          C.AST.ArraySubscript(data, C.AST.ArithmeticExpr(i))

        case (ArrayType(_, vt: VectorType), i :: j :: Nil) =>
          C.AST.ArraySubscript(generateAccess(vt, identifier, j :: Nil, env), C.AST.ArithmeticExpr(i))

        case (_: ArrayType, _) | (_: DepArrayType, _) =>
          val idx = computeArrayIndex(dt, path)
          C.AST.ArraySubscript(identifier, C.AST.ArithmeticExpr(idx))

        case _ =>
          throw new Exception(s"Can't generate access for `$dt' with `${path.mkString("[", "::", "]")}'")
      }
    }

    def computeArrayIndex(at: DataType, path: Path): Nat = {
      (at, path) match {
        case (ArrayType(_, _: BasicType), i :: Nil) => i
        case (DepArrayType(_, _, _: BasicType), i :: Nil) => i

        case (ArrayType(_, et), i :: is) =>
          val colIdx = computeArrayIndex(et, is)
          val rowIdx = i * DataType.getLength(et)
          rowIdx + colIdx

        case (DepArrayType(_, k, et), i :: is) =>
          val colIdx = computeArrayIndex(et, is)
          val rowIdx = BigSum(from = 0, upTo = i - 1, `for` = k, `in` = DataType.getLength(et))
          rowIdx + colIdx

        case _ => ???
      }
    }

    //  private def generateArrayAccess(at: ArrayType, identifier: C.AST.DeclRef, path: Path, index: Nat): Expr = {
    //    (at, path) match {
    //      case (ArrayType(_, bt: BasicType), i :: Nil) =>
    //        C.AST.ArraySubscript(generateAccess(bt, identifier, Nil), C.AST.ArithmeticExpr(i + index))
    //
    //      case (ArrayType(_, vt: VectorType), i :: j :: Nil) =>
    //        C.AST.ArraySubscript(generateAccess(vt, identifier, j :: Nil), C.AST.ArithmeticExpr(i + index))
    //
    //      case (ArrayType(_, et@ArrayType(s, _)), i :: ps) =>
    //        generateArrayAccess(et, identifier, ps, (i * s) + index)
    //
    //      case _ =>
    //        throw new Exception(s"Can't generate access for `$at' with `${path.mkString("[", "::", "]")}'")
    //    }
    //  }

    implicit def convertBinaryOp(op: idealised.SurfaceLanguage.Operators.Binary.Value): idealised.C.AST.BinaryOperator.Value = {
      import idealised.SurfaceLanguage.Operators.Binary._
      op match {
        case ADD => C.AST.BinaryOperator.+
        case SUB => C.AST.BinaryOperator.-
        case MUL => C.AST.BinaryOperator.*
        case DIV => C.AST.BinaryOperator./
        case MOD => ???
        case GT => C.AST.BinaryOperator.>
        case LT => C.AST.BinaryOperator.<
        case EQ => C.AST.BinaryOperator.==
      }
    }

    implicit def convertUnaryOp(op: idealised.SurfaceLanguage.Operators.Unary.Value): idealised.C.AST.UnaryOperator.Value = {
      import idealised.SurfaceLanguage.Operators.Unary._
      op match {
        case NEG => C.AST.UnaryOperator.-
      }
    }

    def makePointerDecl(name: String,
                        elemType: DataType,
                        expr: Expr): Stmt = {
      import C.AST._
      DeclStmt(
        VarDecl(name, PointerType(typ(elemType)), Some(expr)))
    }
  }

  protected def applySubstitutions(n: Nat,
                                   identEnv: immutable.Map[Identifier[_ <: BasePhraseTypes], C.AST.DeclRef]): Nat = {
    // lift the substitutions from the Phrase level to the ArithExpr level
    val substitionMap = identEnv.filter(_._1.t match {
      case ExpType(IndexType(_)) => true
      case AccType(IndexType(_)) => true
      case _ => false
    }).map(i => (NamedVar(i._1.name), NamedVar(i._2.name))).toMap[ArithExpr, ArithExpr]
    ArithExpr.substitute(n, substitionMap)
  }
}

