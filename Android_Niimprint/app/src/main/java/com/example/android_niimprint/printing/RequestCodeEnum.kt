package com.example.android_niimprint.printing

enum class RequestCodeEnum(val value: Int) {
    GET_INFO(64),
    GET_RFID(26),
    HEARTBEAT(220),
    SET_LABEL_TYPE(35),
    SET_LABEL_DENSITY(33),
    START_PRINT(1),
    END_PRINT(243),
    START_PAGE_PRINT(3),
    END_PAGE_PRINT(227),
    ALLOW_PRINT_CLEAR(32),
    SET_DIMENSION(19),
    SET_QUANTITY(21),
    GET_PRINT_STATUS(163)
}