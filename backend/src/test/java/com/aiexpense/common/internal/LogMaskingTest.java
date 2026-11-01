package com.aiexpense.common.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogMaskingTest {

    // Values below are made up for the test; none of them is a real credential.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            password=hunter2-fake                      | password=NOT-MASKED-deliberately-broken
            login failed, Password: hunter2-fake       | login failed, Password: ***
            {"password":"hunter2-fake","email":"a@b.c"} | {"password":"***","email":"a@b.c"}
            refreshToken=abc.def-fake                  | refreshToken=***
            access_token=abc-fake&x=1                  | access_token=***&x=1
            Authorization: Bearer abc.def.ghi-fake     | Authorization: Bearer ***
            apiKey=fake-value                          | apiKey=***
            key sk-ant-api03-FAKEFAKEFAKE in text      | key *** in text
            jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl here | jwt *** here
            nothing secret here                        | nothing secret here
            """)
    void masksSecrets(String input, String expected) {
        assertThat(LogMasking.mask(input)).isEqualTo(expected);
    }
}
