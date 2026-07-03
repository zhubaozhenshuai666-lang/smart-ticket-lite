package com.zewbby.smartticket.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MqConsumeTrace {

    String topic();

    String consumerGroup();

    String messageId() default "";

    String businessKey() default "";

    long slowThresholdMs() default 1000L;
}
