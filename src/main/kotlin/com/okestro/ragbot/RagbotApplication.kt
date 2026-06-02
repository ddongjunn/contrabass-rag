package com.okestro.ragbot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RagbotApplication

fun main(args: Array<String>) {
	runApplication<RagbotApplication>(*args)
}
