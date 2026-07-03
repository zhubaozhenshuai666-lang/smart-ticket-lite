package com.zewbby.smartticket.aop;

import com.zewbby.smartticket.enums.AdminOperationTypeEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    AdminOperationTypeEnum operation();

    String resourceType();

    String resourceId() default "";

    String resultId() default "";
}
