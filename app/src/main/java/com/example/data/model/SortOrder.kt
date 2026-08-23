package com.example.data.model

enum class SortOrder(val displayName: String) {
    DEFAULT("جدیدترین"),
    LOWEST_PING("کمترین تاخیر (پینگ)"),
    NAME("نام سرور (الفبا)"),
    PROTOCOL("نوع پروتکل")
}
