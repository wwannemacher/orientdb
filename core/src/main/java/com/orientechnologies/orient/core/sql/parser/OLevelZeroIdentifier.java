/* Generated By:JJTree: Do not edit this line. OLevelZeroIdentifier.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.sql.executor.AggregationContext;
import com.orientechnologies.orient.core.sql.executor.OResult;

import java.util.Map;
import java.util.Set;

public class OLevelZeroIdentifier extends SimpleNode {
  protected OFunctionCall functionCall;
  protected Boolean       self;
  protected OCollection   collection;

  public OLevelZeroIdentifier(int id) {
    super(id);
  }

  public OLevelZeroIdentifier(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (functionCall != null) {
      functionCall.toString(params, builder);
    } else if (Boolean.TRUE.equals(self)) {
      builder.append("@this");
    } else if (collection != null) {
      collection.toString(params, builder);
    }
  }

  public Object execute(OIdentifiable iCurrentRecord, OCommandContext ctx) {
    if (functionCall != null) {
      return functionCall.execute(iCurrentRecord, ctx);
    }
    if (collection != null) {
      return collection.execute(iCurrentRecord, ctx);
    }
    if (Boolean.TRUE.equals(self)) {
      return iCurrentRecord;
    }
    throw new UnsupportedOperationException();
  }

  public Object execute(OResult iCurrentRecord, OCommandContext ctx) {
    if (functionCall != null) {
      return functionCall.execute(iCurrentRecord, ctx);
    }
    if (collection != null) {
      return collection.execute(iCurrentRecord, ctx);
    }
    if (Boolean.TRUE.equals(self)) {
      return iCurrentRecord;
    }
    throw new UnsupportedOperationException();
  }

  public boolean isIndexedFunctionCall() {
    if (functionCall != null) {
      return functionCall.isIndexedFunctionCall();
    }
    return false;
  }

  public long estimateIndexedFunction(OFromClause target, OCommandContext context, OBinaryCompareOperator operator, Object right) {
    if (functionCall != null) {
      return functionCall.estimateIndexedFunction(target, context, operator, right);
    }

    return -1;
  }

  public Iterable<OIdentifiable> executeIndexedFunction(OFromClause target, OCommandContext context,
      OBinaryCompareOperator operator, Object right) {
    if (functionCall != null) {
      return functionCall.executeIndexedFunction(target, context, operator, right);
    }
    return null;
  }

  public boolean isExpand() {
    if (functionCall != null) {
      return functionCall.isExpand();
    }
    return false;
  }

  public OExpression getExpandContent() {
    if (functionCall.getParams().size() != 1) {
      throw new OCommandExecutionException("Invalid expand expression: " + functionCall.toString());
    }
    return functionCall.getParams().get(0);
  }

  public boolean needsAliases(Set<String> aliases) {
    if (functionCall != null && functionCall.needsAliases(aliases)) {
      return true;
    }
    if (collection != null && collection.needsAliases(aliases)) {
      return true;
    }
    return false;
  }

  public boolean isAggregate() {
    if (functionCall != null && functionCall.isAggregate()) {
      return true;
    }
    if (collection != null && collection.isAggregate()) {
      return true;
    }
    return false;
  }

  public boolean isEarlyCalculated() {
    if (functionCall != null && functionCall.isEarlyCalculated()) {
      return true;
    }
    if (Boolean.TRUE.equals(self)) {
      return false;
    }
    if (collection != null && collection.isEarlyCalculated()) {
      return true;
    }
    return false;
  }

  public SimpleNode splitForAggregation(AggregateProjectionSplit aggregateProj) {
    if (isAggregate()) {
      OLevelZeroIdentifier result = new OLevelZeroIdentifier(-1);
      if (functionCall != null) {
        SimpleNode node = functionCall.splitForAggregation(aggregateProj);
        if (node instanceof OFunctionCall) {
          result.functionCall = (OFunctionCall) node;
        } else {
          return node;
        }
      } else if (collection != null) {
        result.collection = collection.splitForAggregation(aggregateProj);
        return result;
      } else {
        throw new IllegalStateException();
      }
      return result;
    } else {
      return this;
    }
  }

  public AggregationContext getAggregationContext(OCommandContext ctx) {
    if (isAggregate()) {
      OLevelZeroIdentifier result = new OLevelZeroIdentifier(-1);
      if (functionCall != null) {
        return functionCall.getAggregationContext(ctx);
      }
    }
    throw new OCommandExecutionException("cannot aggregate on " + toString());
  }
}
/* JavaCC - OriginalChecksum=0305fcf120ba9395b4c975f85cdade72 (do not edit this line) */
