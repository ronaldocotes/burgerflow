package com.burgerflow

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableCaching
@EnableAsync
class BurgerFlowApplication

fun main(args: Array<String>) {
    runApplication<BurgerFlowApplication>(*args)
}
