package elevate.rise.strategies

import elevate.core.strategies.Traversable
import elevate.core.{RewriteResult, Strategy}
import elevate.core.strategies.basic._
import elevate.core.strategies.predicate._
import elevate.core.strategies.traversal.one
import elevate.rise.rules.traversal._
import elevate.rise.Rise
import elevate.rise.strategies.predicate._
import elevate.rise.rules._
import elevate.rise.rules.algorithmic._
import elevate.rise.rules.traversal.{argumentOf, body, function}
import rise.core.primitives._
import elevate.rise.strategies.traversal._

// todo think about better names!
object normalForm {

  // Beta-Eta-Normal-Form
  case class BENF()(implicit ev: Traversable[Rise]) extends Strategy[Rise] {
    def apply(e: Rise): RewriteResult[Rise] = normalize.apply(etaReduction() <+ betaReduction)(e)
    override def toString = "BENF"
  }

  // Data-Flow-Normal-Form
  case class DFNF()(implicit ev: Traversable[Rise]) extends Strategy[Rise] {
    def apply(e: Rise): RewriteResult[Rise] =
      (BENF() `;`
        // there is no argument of a map which is not eta-abstracted, i.e., every argument of a map is a lambda
        normalize.apply(argumentOf(Map()(), (not(isLambda) `;` etaAbstraction))) `;`
        // a reduce always contains two lambdas declaring y and acc
        normalize.apply(argumentOf(Reduce()(), (not(isLambda) `;` etaAbstraction))) `;`
        normalize.apply(argumentOf(Reduce()(), body((not(isLambda) `;` etaAbstraction)))) `;`
        // there is no map(f) without an argument == there is no way to get to a map without visiting two applies
        // same for reduce and three applies
        normalize.apply(
          one(function(isMap) <+ one(function(isReduce))) `;`                  // there is a map in two hops, i.e, Something(Apply(map, f))
            not(isApply) `;`                                                      // and the current node is not an Apply i.e. Something != Apply
            one((function(isMap) <+ one(function(isReduce))) `;` etaAbstraction)   // eta-abstract the inner Apply
        ))(e)
    override def toString = "LCNF"
  }

  // Rewrite-Normal-Form (Fission all maps)
  case class RNF()(implicit ev: Traversable[Rise]) extends Strategy[Rise] {
    def apply(e: Rise): RewriteResult[Rise] = (normalize.apply(DFNF() `;` mapLastFission) `;` DFNF())(e)
    override def toString = "RNF"
  }

  // Codegen-Normal-Form (Fuse all maps)
  case class CNF()(implicit ev: Traversable[Rise]) extends Strategy[Rise] {
    def apply(e: Rise): RewriteResult[Rise] = normalize.apply(mapFusion)(e)
    override def toString = "CNF"
  }
}
