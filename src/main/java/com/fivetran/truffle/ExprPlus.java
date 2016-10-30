package com.fivetran.truffle;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

@NodeInfo(shortName = "+")
public abstract class ExprPlus extends ExprBinary {

    @Specialization
    protected long add(long left, long right) {
        // Don't use ExactMath because we don't want to throw ArithmeticException
        return left + right;
    }

    @Specialization
    protected double add(double left, double right) {
        return left + right;
    }
}
