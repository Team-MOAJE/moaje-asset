package com.moaje.asset.common.id

import org.hibernate.annotations.IdGeneratorType

@IdGeneratorType(TsidIdentifierGenerator::class)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class TsidGeneratedValue

