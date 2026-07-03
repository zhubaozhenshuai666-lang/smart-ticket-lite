package com.zewbby.smartticket.aop;

import org.slf4j.Logger;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

final class AspectExpressionResolver {

    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    String resolve(Method method, Object[] args, Object result, String expression, Logger logger) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        String trimmedExpression = expression.trim();
        if (!isExpression(trimmedExpression)) {
            return trimmedExpression;
        }
        try {
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    null,
                    method,
                    args,
                    parameterNameDiscoverer
            );
            context.setVariable("result", result);
            Object value = expressionParser.parseExpression(trimmedExpression).getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException exception) {
            logger.warn("Failed to resolve aspect expression, method={}, expression={}",
                    method.getName(), trimmedExpression, exception);
            return trimmedExpression;
        }
    }

    private boolean isExpression(String expression) {
        return expression.startsWith("#")
                || expression.startsWith("'")
                || expression.startsWith("\"");
    }
}
