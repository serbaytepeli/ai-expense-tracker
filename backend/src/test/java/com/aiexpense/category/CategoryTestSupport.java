package com.aiexpense.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Request builders for the category API. */
final class CategoryTestSupport {

    static final String BASE = "/api/v1/categories";

    private CategoryTestSupport() {
    }

    static MockHttpServletRequestBuilder list(String token) {
        return get(BASE).header("Authorization", "Bearer " + token);
    }

    static MockHttpServletRequestBuilder listAll(String token) {
        return list(token).param("includeArchived", "true");
    }

    static MockHttpServletRequestBuilder create(String token, String json) {
        return post(BASE).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder create(String token, String name, String icon, String color) {
        return create(token, "{\"name\":\"" + name + "\",\"icon\":\"" + icon + "\",\"color\":\"" + color + "\"}");
    }

    static MockHttpServletRequestBuilder update(String token, String id, String json) {
        return patch(BASE + "/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder archive(String token, String id) {
        return post(BASE + "/" + id + "/archive").header("Authorization", "Bearer " + token);
    }

    static MockHttpServletRequestBuilder unarchive(String token, String id) {
        return post(BASE + "/" + id + "/unarchive").header("Authorization", "Bearer " + token);
    }
}
