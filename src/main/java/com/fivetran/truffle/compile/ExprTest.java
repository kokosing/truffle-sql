package com.fivetran.truffle.compile;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * Wraps an expression that will be used in a test, for example CASE WHEN ? THEN 1 ELSE 0,
 * and converts anything other than TRUE to FALSE.
 */
@NodeChild("target")
abstract class ExprTest extends ExprBase {
    @Specialization
    public boolean executeBoolean(boolean value) {
        return value;
    }

    @Specialization
    public boolean executeObject(Object any) {
        return false;
    }
}
