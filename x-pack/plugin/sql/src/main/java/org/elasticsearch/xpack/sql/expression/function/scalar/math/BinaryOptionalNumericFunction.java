/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.expression.function.scalar.math;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.Expressions;
import org.elasticsearch.xpack.sql.expression.Expressions.ParamOrdinal;
import org.elasticsearch.xpack.sql.expression.function.scalar.ScalarFunction;
import org.elasticsearch.xpack.sql.expression.function.scalar.math.BinaryOptionalMathProcessor.BinaryOptionalMathOperation;
import org.elasticsearch.xpack.sql.expression.gen.pipeline.Pipe;
import org.elasticsearch.xpack.sql.expression.gen.script.ScriptTemplate;
import org.elasticsearch.xpack.sql.tree.Source;
import org.elasticsearch.xpack.sql.type.DataType;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import static java.lang.String.format;
import static org.elasticsearch.xpack.sql.expression.TypeResolutions.isNumeric;
import static org.elasticsearch.xpack.sql.expression.gen.script.ParamsBuilder.paramsBuilder;

public abstract class BinaryOptionalNumericFunction extends ScalarFunction {

    private final Expression left, right;
    
    public BinaryOptionalNumericFunction(Source source, Expression left, Expression right) {
        super(source, right != null ? Arrays.asList(left, right) : Arrays.asList(left));
        this.left = left;
        this.right = right;
    }
    
    @Override
    protected TypeResolution resolveType() {
        if (!childrenResolved()) {
            return new TypeResolution("Unresolved children");
        }

        TypeResolution resolution = isNumeric(left, sourceText(), ParamOrdinal.FIRST);
        if (resolution.unresolved()) {
            return resolution;

        }

        return right == null ? TypeResolution.TYPE_RESOLVED : isNumeric(right, sourceText(), ParamOrdinal.SECOND);
    }

    @Override
    protected Pipe makePipe() {
        return new BinaryOptionalMathPipe(source(), this,
            Expressions.pipe(left),
            right == null ? null : Expressions.pipe(right),
            operation());
    }
    
    protected abstract BinaryOptionalMathOperation operation();

    @Override
    public boolean foldable() {
        return left.foldable()
                && (right == null || right.foldable());
    }

    @Override
    public Object fold() {
        return operation().apply((Number) left.fold(), (right == null ? null : (Number) right.fold()));
    }
    
    @Override
    public ScriptTemplate asScript() {
        ScriptTemplate leftScript = asScript(left);
        ScriptTemplate rightScript = right == null ? null : asScript(right);

        return asScriptFrom(leftScript, rightScript);
    }

    private ScriptTemplate asScriptFrom(ScriptTemplate leftScript, ScriptTemplate rightScript) {
        if (right == null) {
            return new ScriptTemplate(format(Locale.ROOT, formatTemplate("{sql}.%s(%s)"),
                    operation().name().toLowerCase(Locale.ROOT),
                    leftScript.template()),
                    paramsBuilder()
                        .script(leftScript.params())
                        .build(), dataType());
        }
        return new ScriptTemplate(format(Locale.ROOT, formatTemplate("{sql}.%s(%s,%s)"),
                operation().name().toLowerCase(Locale.ROOT),
                leftScript.template(),
                rightScript.template()),
                paramsBuilder()
                    .script(leftScript.params()).script(rightScript.params())
                    .build(), dataType());
    }

    @Override
    public DataType dataType() {
        return left().dataType();
    }
    
    protected Expression left() {
        return left;
    }
    
    protected Expression right() {
        return right;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(left(), right(), operation());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        BinaryOptionalNumericFunction other = (BinaryOptionalNumericFunction) obj;
        return Objects.equals(other.left(), left())
            && Objects.equals(other.right(), right())
            && Objects.equals(other.operation(), operation());
    }
}
