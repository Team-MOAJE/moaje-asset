package com.moaje.asset.common.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory) : RedisTemplate<String, Any> {

        val template = RedisTemplate<String,Any>()
        template.connectionFactory = connectionFactory

        // key??String?쇰줈 吏곷젹??(Redis?먯꽌 key瑜?源붾걫?섍쾶 蹂닿린?꾪빐??
        template.keySerializer = StringRedisSerializer()

        // value??JSON?쇰줈 吏곷젹??(TokenResponse 媛앹껜瑜?JSON?쇰줈 ???
        template.valueSerializer = Jackson2JsonRedisSerializer(Any::class.java)

        return template
    }
}
