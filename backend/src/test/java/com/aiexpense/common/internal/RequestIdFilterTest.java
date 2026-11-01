package com.aiexpense.common.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexpense.common.RequestIds;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "client-abc_123.x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("client-abc_123.x");
    }

    @ParameterizedTest(name = "replaces malformed incoming id [{0}]")
    @ValueSource(strings = {"", "has space", "line\nbreak", "<script>",
            "x12345678901234567890123456789012345678901234567890123456789012345"})
    void replacesMalformedIncomingId(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader(RequestIds.HEADER);
        assertThat(id).isNotEqualTo(incoming);
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void generatesIdWhenMissingAndExposesItInMdcOnlyDuringTheRequest() throws Exception {
        AtomicReference<String> seenInMdc = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (req, res) -> seenInMdc.set(MDC.get(RequestIds.MDC_KEY)));

        assertThat(seenInMdc.get()).isEqualTo(response.getHeader(RequestIds.HEADER));
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }
}
