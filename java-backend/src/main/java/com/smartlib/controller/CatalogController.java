package com.smartlib.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.Input;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.CatalogService;

/** /api/books, /api/authors, /api/categories and /api/shelves. Reading is open to every signed-in user. */
@RestController
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /* --------------------------------- Books --------------------------------- */

    @GetMapping("/api/books")
    public Map<String, Object> listBooks(@RequestParam Map<String, String> q) {
        return catalog.listBooks(Input.of(q));
    }

    @GetMapping("/api/books/meta")
    public Map<String, Object> bookMeta() {
        return catalog.bookMeta();
    }

    @GetMapping("/api/books/recommendations")
    public Map<String, Object> recommendations(CurrentUser user, @RequestParam Map<String, String> q) {
        return catalog.recommendations(user, Input.of(q));
    }

    @PostMapping("/api/books/bulk")
    public Map<String, Object> bulkBooks(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.bulkBooks(Input.of(body));
    }

    @GetMapping("/api/books/{id}")
    public Map<String, Object> getBook(CurrentUser user, @PathVariable String id) {
        return catalog.getBook(user, id);
    }

    @PostMapping("/api/books")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBook(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.createBook(user, Input.of(body));
    }

    @PutMapping("/api/books/{id}")
    public Map<String, Object> updateBook(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.updateBook(Input.of(body), id);
    }

    @PatchMapping("/api/books/{id}/location")
    public Map<String, Object> moveBook(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireStaff();
        return catalog.moveBook(Input.of(body), id);
    }

    @DeleteMapping("/api/books/{id}")
    public Map<String, Object> deleteBook(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return catalog.deleteBook(id);
    }

    /* -------------------------------- Authors -------------------------------- */

    @GetMapping("/api/authors")
    public Map<String, Object> listAuthors(@RequestParam Map<String, String> q) {
        return catalog.listAuthors(Input.of(q));
    }

    @GetMapping("/api/authors/{id}")
    public Map<String, Object> getAuthor(@PathVariable String id) {
        return catalog.getAuthor(id);
    }

    @PostMapping("/api/authors")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAuthor(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.createAuthor(Input.of(body));
    }

    @PutMapping("/api/authors/{id}")
    public Map<String, Object> updateAuthor(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.updateAuthor(Input.of(body), id);
    }

    @DeleteMapping("/api/authors/{id}")
    public Map<String, Object> deleteAuthor(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return catalog.deleteAuthor(id);
    }

    /* ------------------------------- Categories ------------------------------- */

    @GetMapping("/api/categories")
    public Map<String, Object> listCategories() {
        return catalog.listCategories();
    }

    @GetMapping("/api/categories/{id}")
    public Map<String, Object> getCategory(@PathVariable String id) {
        return catalog.getCategory(id);
    }

    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.createCategory(Input.of(body));
    }

    @PutMapping("/api/categories/{id}")
    public Map<String, Object> updateCategory(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.updateCategory(Input.of(body), id);
    }

    @DeleteMapping("/api/categories/{id}")
    public Map<String, Object> deleteCategory(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return catalog.deleteCategory(id);
    }

    /* -------------------------------- Shelves -------------------------------- */

    @GetMapping("/api/shelves")
    public Map<String, Object> listShelves(@RequestParam Map<String, String> q) {
        return catalog.listShelves(Input.of(q));
    }

    @GetMapping("/api/shelves/{id}")
    public Map<String, Object> getShelf(@PathVariable String id) {
        return catalog.getShelf(id);
    }

    @PostMapping("/api/shelves")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createShelf(CurrentUser user, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.createShelf(Input.of(body));
    }

    @PutMapping("/api/shelves/{id}")
    public Map<String, Object> updateShelf(CurrentUser user, @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        user.requireEditor();
        return catalog.updateShelf(Input.of(body), id);
    }

    @DeleteMapping("/api/shelves/{id}")
    public Map<String, Object> deleteShelf(CurrentUser user, @PathVariable String id) {
        user.requireEditor();
        return catalog.deleteShelf(id);
    }
}
