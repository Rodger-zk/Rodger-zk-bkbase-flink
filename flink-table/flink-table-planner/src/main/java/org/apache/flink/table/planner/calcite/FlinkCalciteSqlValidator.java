/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.calcite;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.planner.functions.bridging.BridgingSqlFunction;
import org.apache.flink.table.types.logical.DecimalType;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlPostfixOperator;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWindowTableFunction;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlCaseOperator;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.util.Static;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.apache.calcite.sql.type.SqlTypeName.DECIMAL;

/** Extends Calcite's {@link SqlValidator} by Flink-specific behavior. */
@Internal
public final class FlinkCalciteSqlValidator extends SqlValidatorImpl {

    // Enables CallContext#getOutputDataType() when validating SQL expressions.
    private SqlNode sqlNodeForExpectedOutputType;
    private RelDataType expectedOutputType;

    public FlinkCalciteSqlValidator(
            SqlOperatorTable opTab,
            SqlValidatorCatalogReader catalogReader,
            RelDataTypeFactory typeFactory,
            SqlValidator.Config config) {
        super(opTab, catalogReader, typeFactory, config);
    }

    public void setExpectedOutputType(SqlNode sqlNode, RelDataType expectedOutputType) {
        this.sqlNodeForExpectedOutputType = sqlNode;
        this.expectedOutputType = expectedOutputType;
    }

    public Optional<RelDataType> getExpectedOutputType(SqlNode sqlNode) {
        if (sqlNode == sqlNodeForExpectedOutputType) {
            return Optional.of(expectedOutputType);
        }
        return Optional.empty();
    }

    @Override
    public void validateLiteral(SqlLiteral literal) {
        if (literal.getTypeName() == DECIMAL) {
            final BigDecimal decimal = literal.getValueAs(BigDecimal.class);
            if (decimal.precision() > DecimalType.MAX_PRECISION) {
                throw newValidationError(
                        literal, Static.RESOURCE.numberLiteralOutOfRange(decimal.toString()));
            }
        }
        super.validateLiteral(literal);
    }

    @Override
    protected void validateJoin(SqlJoin join, SqlValidatorScope scope) {
        // Due to the improper translation of lateral table left outer join in Calcite, we need to
        // temporarily forbid the common predicates until the problem is fixed (see FLINK-7865).
        if (join.getJoinType() == JoinType.LEFT
                && SqlUtil.stripAs(join.getRight()).getKind() == SqlKind.COLLECTION_TABLE) {
            SqlNode right = SqlUtil.stripAs(join.getRight());
            if (right instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) right;
                SqlNode operand0 = call.operand(0);
                if (operand0 instanceof SqlBasicCall
                        && ((SqlBasicCall) operand0).getOperator()
                                instanceof SqlWindowTableFunction) {
                    return;
                }
            }
            final SqlNode condition = join.getCondition();
            if (condition != null
                    && (!SqlUtil.isLiteral(condition)
                            || ((SqlLiteral) condition).getValueAs(Boolean.class)
                                    != Boolean.TRUE)) {
                throw new ValidationException(
                        String.format(
                                "Left outer joins with a table function do not accept a predicate such as %s. "
                                        + "Only literal TRUE is accepted.",
                                condition));
            }
        }
        super.validateJoin(join, scope);
    }

    @Override
    public void validateColumnListParams(
            SqlFunction function, List<RelDataType> argTypes, List<SqlNode> operands) {
        // we don't support column lists and translate them into the unknown type in the type
        // factory,
        // this makes it possible to ignore them in the validator and fall back to regular row types
        // see also SqlFunction#deriveType
    }

    @Override
    protected SqlNode performUnconditionalRewrites(SqlNode node, boolean underFrom) {
        if (node == null) {
            return null;
        }
        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            if (call.getOperator() instanceof SqlUnresolvedFunction) {
                assert call instanceof SqlBasicCall;
                final SqlUnresolvedFunction function = (SqlUnresolvedFunction) call.getOperator();
                final List<SqlOperator> overloads = new ArrayList<>();
                getOperatorTable()
                        .lookupOperatorOverloads(
                                function.getNameAsId(),
                                function.getFunctionType(),
                                SqlSyntax.FUNCTION,
                                overloads,
                                getCatalogReader().nameMatcher());
                if (overloads.size() > 1
                        && overloads.stream().allMatch(func -> func instanceof SqlAggFunction)) {
                    ((SqlBasicCall) call).setOperator(overloads.get(0));
                }
            } else if (call instanceof SqlBasicCall
                    && checkOperatorOverloadable((SqlBasicCall) call)) {
                // Allow overloading built-in operator, e.g. +/-/like
                SqlIdentifier functionName = getFunctionName(call);
                SqlFunctionCategory functionCategory = SqlFunctionCategory.SYSTEM;
                final List<SqlOperator> overloads = new ArrayList<>();
                getOperatorTable()
                        .lookupOperatorOverloads(
                                functionName,
                                functionCategory,
                                SqlSyntax.FUNCTION,
                                overloads,
                                getCatalogReader().nameMatcher());
                if (overloads.size() > 0) {
                    SqlOperator operator = overloads.get(0);
                    if (operator instanceof BridgingSqlFunction) {
                        BridgingSqlFunction function = (BridgingSqlFunction) operator;
                        operator =
                                BridgingSqlFunction.of(
                                        function.getDataTypeFactory(),
                                        function.getTypeFactory(),
                                        call.getOperator().getKind(),
                                        function.getResolvedFunction(),
                                        function.getTypeInference());
                    }
                    ((SqlBasicCall) call).setOperator(operator);
                }
            } else if (call.getOperator() instanceof SqlCaseOperator) {
                // Allow overloading case operator
                assert call instanceof SqlCase;
                SqlIdentifier functionName;
                List<SqlNode> operandList = new ArrayList<>();
                if (((SqlCase) call).getValueOperand() != null) {
                    // CASE a WHEN b THEN c ELSE d END
                    functionName = new SqlIdentifier("CASE", SqlParserPos.ZERO);
                    operandList.add(((SqlCase) call).getValueOperand());
                } else {
                    // CASE WHEN a THEN b ELSE c END
                    functionName = new SqlIdentifier("WHEN", SqlParserPos.ZERO);
                }
                // Operand list: when-1, then-1, when-2, then-2...else
                Iterator<SqlNode> whenIterator = ((SqlCase) call).getWhenOperands().iterator();
                Iterator<SqlNode> thenIterator = ((SqlCase) call).getThenOperands().iterator();
                while (whenIterator.hasNext() && thenIterator.hasNext()) {
                    operandList.add(whenIterator.next());
                    operandList.add(thenIterator.next());
                }
                operandList.add(((SqlCase) call).getElseOperand());
                if (isCaseCallOverloadable(operandList)) {
                    SqlFunctionCategory functionCategory = SqlFunctionCategory.SYSTEM;
                    final List<SqlOperator> overloads = new ArrayList<>();
                    getOperatorTable()
                            .lookupOperatorOverloads(
                                    functionName,
                                    functionCategory,
                                    SqlSyntax.FUNCTION,
                                    overloads,
                                    getCatalogReader().nameMatcher());
                    if (overloads.size() > 0) {
                        // rewrite case call
                        node =
                                new SqlBasicCall(
                                        overloads.get(0),
                                        operandList.toArray(new SqlNode[0]),
                                        call.getParserPosition());
                    }
                }
            } else if (call.getOperator() instanceof SqlUnresolvedFunction
                    && call.getOperator().getName().equalsIgnoreCase("IF")) {
                assert call instanceof SqlBasicCall;
                if (isIfCallOverloadable((SqlBasicCall) call)) {
                    rewriteOperator((SqlBasicCall) call);
                }
            }
        }
        return super.performUnconditionalRewrites(node, underFrom);
    }

    private Boolean checkOperatorOverloadable(SqlBasicCall call) {
        List<SqlKind> operatorKindBlacklist = Arrays.asList(SqlKind.IN);
        SqlKind kind = call.getOperator().getKind();
        return (call.getOperator() instanceof SqlBinaryOperator
                        || call.getOperator() instanceof SqlLikeOperator
                        || call.getOperator() instanceof SqlPostfixOperator)
                && !operatorKindBlacklist.contains(kind);
    }

    private SqlIdentifier getFunctionName(SqlCall call) {
        switch (call.getOperator().getName().toUpperCase()) {
            case "IS NULL":
                return new SqlIdentifier("isNull", SqlParserPos.ZERO);
            case "IS NOT NULL":
                return new SqlIdentifier("isNotNull", SqlParserPos.ZERO);
            default:
                return call.getOperator().getNameAsId();
        }
    }

    private boolean isCaseCallOverloadable(List<SqlNode> operandList) {
        if (operandList.size() % 2 == 0) {
            for (int i = 2; i < operandList.size(); ++i) {
                if (isOtherFunctionCall(operandList.get(i))) {
                    return false;
                }
            }
        } else {
            for (int i = 1; i < operandList.size(); ++i) {
                if (isOtherFunctionCall(operandList.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isIfCallOverloadable(SqlBasicCall ifCall) {
        if (ifCall.getOperandList().size() != 3) {
            return false;
        }
        SqlNode trueReturn = ifCall.getOperands()[1];
        SqlNode falseReturn = ifCall.getOperands()[2];
        return !(isOtherFunctionCall(trueReturn)) && !(isOtherFunctionCall(falseReturn));
    }

    private boolean isOtherFunctionCall(SqlNode node) {
        return node instanceof SqlCall
                && (SqlKind.FUNCTION.contains(((SqlCall) node).getOperator().getKind())
                        || ((SqlCall) node)
                                .getOperandList().stream().anyMatch(this::isOtherFunctionCall));
    }

    private void rewriteOperator(SqlBasicCall call) {
        final SqlUnresolvedFunction function = (SqlUnresolvedFunction) call.getOperator();
        final List<SqlOperator> overloads = new ArrayList<>();
        getOperatorTable()
                .lookupOperatorOverloads(
                        function.getNameAsId(),
                        function.getFunctionType(),
                        SqlSyntax.FUNCTION,
                        overloads,
                        getCatalogReader().nameMatcher());
        if (overloads.size() > 0) {
            call.setOperator(overloads.get(0));
        }
    }

    @Override
    public RelDataType deriveType(SqlValidatorScope scope, SqlNode expr) {
        List<SqlKind> operatorKindWhitelist = new ArrayList<>(SqlKind.BINARY_COMPARISON);
        operatorKindWhitelist.add(SqlKind.AND);
        operatorKindWhitelist.add(SqlKind.OR);
        RelDataType type;
        if (expr instanceof SqlBasicCall
                && ((SqlCall) expr).getOperator() instanceof BridgingSqlFunction
                && operatorKindWhitelist.contains(((SqlCall) expr).getOperator().getKind())) {
            SqlBasicCall call = (SqlBasicCall) expr;
            SqlKind kind = call.getOperator().getKind();
            type = super.deriveType(scope, expr);
            BridgingSqlFunction function = (BridgingSqlFunction) call.getOperator();
            call.setOperator(
                    BridgingSqlFunction.of(
                            function.getDataTypeFactory(),
                            function.getTypeFactory(),
                            kind,
                            function.getResolvedFunction(),
                            function.getTypeInference()));
        } else {
            type = super.deriveType(scope, expr);
        }
        return type;
    }
}
