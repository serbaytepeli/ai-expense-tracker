package com.aiexpense.category.internal;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api-contract.md section 4. The owner is always the JWT subject, never a request field. */
@RestController
@RequestMapping(path = "/api/v1/categories", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "UNAUTHORIZED, TOKEN_EXPIRED or SESSION_REVOKED", content = @Content(mediaType = CategoryController.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
class CategoryController {

    static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    static final String ICON_PATTERN = "^[a-z0-9-]{1,40}$";
    static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";
    static final String NAME_DOC = "Stored trimmed; 1-30 characters after trimming";

    // Name length is checked after trimming in CategoryService; the size limit here only caps input.
    record CreateCategoryRequest(
            @NotNull @Size(max = 200) @Schema(description = NAME_DOC) String name,
            @NotNull @Pattern(regexp = ICON_PATTERN, message = "must be 1-40 lowercase letters, digits or '-'") String icon,
            @NotNull @Pattern(regexp = COLOR_PATTERN, message = "must be a #RRGGBB color") String color) {
    }

    record UpdateCategoryRequest(
            @Size(max = 200) @Schema(description = NAME_DOC) String name,
            @Pattern(regexp = ICON_PATTERN, message = "must be 1-40 lowercase letters, digits or '-'") String icon,
            @Pattern(regexp = COLOR_PATTERN, message = "must be a #RRGGBB color") String color,
            @Min(0) @Max(CategoryService.SORT_ORDER_MAX) Integer sortOrder) {
    }

    // Every field is always present in the response; systemKey is null for user-created categories.
    record CategoryResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String icon,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED, types = {"string", "null"},
                    description = "Set only for built-in categories (for example GROCERIES, OTHER)") String systemKey,
            @Schema(requiredMode = REQUIRED) int sortOrder,
            @Schema(requiredMode = REQUIRED) boolean archived) {
    }

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Categories ordered by sortOrder, then name")
    List<CategoryResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return categories.list(userId(jwt), includeArchived).stream().map(CategoryController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Created at the end of the list")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_NAME_TAKEN or CATEGORY_SORT_ORDER_LIMIT", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    CategoryResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCategoryRequest body) {
        return toResponse(categories.create(userId(jwt), body.name(), body.icon(), body.color()));
    }

    @PatchMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "Updated (archived categories can be edited too)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_NAME_TAKEN", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    CategoryResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody UpdateCategoryRequest body) {
        var changes = new CategoryService.Changes(body.name(), body.icon(), body.color(), body.sortOrder());
        return toResponse(categories.update(userId(jwt), categoryId(id), changes));
    }

    @PostMapping("/{id}/archive")
    @ApiResponse(responseCode = "200", description = "Archived (no-op if already archived)")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_PROTECTED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    CategoryResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return toResponse(categories.archive(userId(jwt), categoryId(id)));
    }

    @PostMapping("/{id}/unarchive")
    @ApiResponse(responseCode = "200", description = "Active again (no-op if not archived)")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_NAME_TAKEN", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    CategoryResponse unarchive(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return toResponse(categories.unarchive(userId(jwt), categoryId(id)));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** A malformed ID can never name an existing category: same 404 as an unknown one. */
    private static UUID categoryId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CategoryService.notFound();
        }
    }

    private static CategoryResponse toResponse(CategoryEntity category) {
        return new CategoryResponse(category.getId(), category.name(), category.icon(), category.color(),
                category.systemKey(), category.sortOrder(), category.archived());
    }
}
