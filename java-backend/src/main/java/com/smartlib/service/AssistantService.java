package com.smartlib.service;

import static com.smartlib.common.Js.money;
import static com.smartlib.common.Js.num;
import static com.smartlib.common.Js.obj;
import static com.smartlib.common.Js.str;
import static com.smartlib.common.Js.truthy;
import static com.smartlib.library.Mappers.BOOK_SQL;
import static com.smartlib.library.Mappers.FINE_SQL;
import static com.smartlib.library.Mappers.LOAN_SQL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.smartlib.common.Db;
import com.smartlib.common.Input;
import com.smartlib.common.Js;
import com.smartlib.common.JsDate;
import com.smartlib.library.Library;
import com.smartlib.library.Library.Settings;
import com.smartlib.library.Mappers;
import com.smartlib.security.CurrentUser;
import com.smartlib.service.CatalogService.SearchResult;

/** Rule-based natural-language assistant: keyword and pattern intents mapped to parameterised SQL. */
@Service
public class AssistantService {

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Map<String, List<String>> CATEGORY_ALIASES = new LinkedHashMap<>();

    static {
        CATEGORY_ALIASES.put("programming", List.of("programming", "coding", "code", "software", "developer", "programozás"));
        CATEGORY_ALIASES.put("science", List.of("science", "scientific", "physics", "biology", "astronomy", "tudomány"));
        CATEGORY_ALIASES.put("technology", List.of("technology", "tech"));
        CATEGORY_ALIASES.put("engineering", List.of("engineering", "engineer"));
        CATEGORY_ALIASES.put("mathematics", List.of("mathematics", "math", "maths", "matematika"));
        CATEGORY_ALIASES.put("history", List.of("history", "historical", "történelem"));
        CATEGORY_ALIASES.put("biography", List.of("biography", "biographies", "autobiography", "memoir"));
        CATEGORY_ALIASES.put("business", List.of("business", "finance", "management"));
        CATEGORY_ALIASES.put("entrepreneurship", List.of("entrepreneurship", "startup", "startups", "entrepreneur"));
        CATEGORY_ALIASES.put("competitive exams", List.of("competitive", "exam", "exams", "upsc", "gate", "aptitude"));
        CATEGORY_ALIASES.put("reference", List.of("reference", "dictionary", "encyclopedia"));
        CATEGORY_ALIASES.put("fiction", List.of("fiction", "novel", "novels", "story", "stories", "regény", "szépirodalom"));
    }

    private static final Set<String> STOP = Set.of(("show me list find get give all any the a an of books book with for please some that are is available which what do you "
        + "have search looking look i want need on in at by").split(" "));

    private static final String PRICE = "(?:₹|rs\\.?|inr)?\\s*(\\d+(?:,\\d{2,3})*(?:\\.\\d+)?)\\s*(?:rupees?|rs\\.?|inr)?";
    private static final Pattern PRICE_BETWEEN = Pattern.compile("between\\s+" + PRICE + "\\s+(?:and|to|-)\\s+" + PRICE, CI);
    private static final Pattern PRICE_UNDER = Pattern.compile("(?:under|below|less than|cheaper than|within|upto|up to|<)\\s*" + PRICE, CI);
    private static final Pattern PRICE_OVER = Pattern.compile("(?:over|above|more than|greater than|>)\\s*" + PRICE, CI);
    private static final Pattern AUTHOR = Pattern.compile(
        "\\b(?:by|author|written by|from author)\\s+(\\p{L}[\\p{L}.\\s'-]+?)(?=\\s+(?:under|below|over|above|on|in|at|between|priced|that|which|with|available)\\b|[?.,!]|$)", CI);
    private static final Pattern FLOOR = Pattern.compile("\\bfloor\\s*(\\d+)|\\b(\\d+)(?:st|nd|rd|th)\\s+floor");
    private static final Pattern SHELF = Pattern.compile("\\bshelf\\s+([a-z]{1,2})\\b", CI);
    private static final Pattern RACK = Pattern.compile("\\brack\\s*(\\d+)");
    private static final Pattern HELP = Pattern.compile("^(hi|hello|hey|help|szia|what can you do|\\?)\\b", CI);
    private static final Pattern LOCATE = Pattern.compile(
        "(?:where\\s+is|where's|where\\s+can\\s+i\\s+find|locate|location\\s+of|which\\s+shelf\\s+(?:is|has)|find\\s+the\\s+book|hol\\s+van)\\s+(.+)", CI);

    private final Db db;
    private final Library lib;
    private final CatalogService catalog;

    public AssistantService(Db db, Library lib, CatalogService catalog) {
        this.db = db;
        this.lib = lib;
        this.catalog = catalog;
    }

    private static boolean find(String regex, String text) {
        return Pattern.compile(regex).matcher(text).find();
    }

    private static String sectionLabel(Object x) {
        String s = x == null ? "" : str(x);
        return s.toLowerCase(Locale.ROOT).endsWith("section") ? s : s + " Section";
    }

    @SuppressWarnings("unchecked")
    private static String locationText(Object loc) {
        if (!(loc instanceof Map<?, ?> m)) return "no shelf location recorded";
        Map<String, Object> l = (Map<String, Object>) m;
        return "Floor " + l.get("floor") + ", " + sectionLabel(l.get("section")) + ", Shelf " + l.get("shelfCode") + ", Rack " + l.get("rack")
            + ", Row " + l.get("row") + ", Position " + l.get("position");
    }

    private static Map<String, Object> aiBook(Map<String, Object> b) {
        return Js.with(b, "locationText", locationText(b.get("location")));
    }

    private static double price(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private record Parsed(Map<String, Object> q, List<String> understood) {
    }

    private Parsed parseQuery(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Map<String, Object> q = new LinkedHashMap<>();
        Matcher m = PRICE_BETWEEN.matcher(lower);
        if (m.find()) {
            q.put("minPrice", price(m.group(1)));
            q.put("maxPrice", price(m.group(2)));
        } else if ((m = PRICE_UNDER.matcher(lower)).find()) {
            q.put("maxPrice", price(m.group(1)));
        } else if ((m = PRICE_OVER.matcher(lower)).find()) {
            q.put("minPrice", price(m.group(1)));
        }
        List<String> u = new ArrayList<>();
        if (q.containsKey("maxPrice") && q.containsKey("minPrice")) u.add("price " + money(q.get("minPrice")) + "–" + money(q.get("maxPrice")));
        else if (q.containsKey("maxPrice")) u.add("price under " + money(q.get("maxPrice")));
        else if (q.containsKey("minPrice")) u.add("price over " + money(q.get("minPrice")));

        for (Map<String, Object> c : db.rows("SELECT name FROM categories")) {
            String name = str(c.get("name"));
            String key = name.toLowerCase(Locale.ROOT);
            List<String> aliases = CATEGORY_ALIASES.getOrDefault(key, List.of(key));
            boolean hit = aliases.stream().anyMatch(a -> Pattern.compile("\\b" + Pattern.quote(a) + "\\b", CI).matcher(lower).find()) || lower.contains(key);
            if (hit) {
                q.put("category", name);
                u.add("category " + name);
                break;
            }
        }
        Matcher au = AUTHOR.matcher(text);
        if (au.find()) {
            q.put("author", au.group(1).strip());
            u.add("author \"" + q.get("author") + "\"");
        }
        Matcher fl = FLOOR.matcher(lower);
        if (fl.find()) {
            long floor = Long.parseLong(fl.group(1) != null ? fl.group(1) : fl.group(2));
            q.put("floor", floor);
            u.add("floor " + floor);
        }
        Matcher sh = SHELF.matcher(text);
        if (sh.find()) {
            q.put("shelf", sh.group(1).toUpperCase(Locale.ROOT));
            u.add("shelf " + q.get("shelf"));
        }
        Matcher rk = RACK.matcher(lower);
        if (rk.find()) {
            q.put("rack", rk.group(1));
            u.add("rack " + Js.padStart(rk.group(1), 2, '0'));
        }
        for (Map<String, Object> r : db.rows("SELECT DISTINCT language FROM books")) {
            String lang = str(r.get("language"));
            if (Pattern.compile("\\b" + Pattern.quote(lang) + "\\b", CI).matcher(lower).find()) {
                q.put("language", lang);
                u.add("language " + lang);
                break;
            }
        }
        if (find("\\b(available|in stock)\\b", lower)) {
            q.put("status", "available");
            u.add("available now");
        } else if (find("\\b(issued|checked out|borrowed)\\b", lower)) {
            q.put("status", "issued");
            u.add("currently issued");
        } else if (find("\\breserved\\b", lower)) {
            q.put("status", "reserved");
            u.add("reserved");
        } else if (find("\\blost\\b", lower)) {
            q.put("status", "lost");
            u.add("lost copies");
        } else if (find("\\bdamaged\\b", lower)) {
            q.put("status", "damaged");
            u.add("damaged copies");
        }
        if (u.isEmpty()) {
            List<String> words = Arrays.stream(lower.replaceAll("[^\\p{L}0-9\\s]", " ").split("\\s+")).filter(w -> !w.isEmpty() && !STOP.contains(w)).toList();
            if (!words.isEmpty()) {
                q.put("search", String.join(" ", words));
                u.add("keywords \"" + q.get("search") + "\"");
            }
        }
        return new Parsed(q, u);
    }

    private List<Map<String, Object>> titleSearch(String term) {
        String clean = term.replaceAll("[?.\"'!]", "").replaceFirst("(?i)^(the book|book)\\s+", "").strip();
        if (clean.isEmpty()) return List.of();
        List<Map<String, Object>> books = db.rows(BOOK_SQL).stream().map(Mappers::bookOut).toList();
        List<Map<String, Object>> r = books.stream().filter(b -> str(b.get("title")).toLowerCase(Locale.ROOT).equals(clean.toLowerCase(Locale.ROOT))).toList();
        if (r.isEmpty()) {
            Pattern p = Pattern.compile(Pattern.quote(clean), CI);
            r = books.stream().filter(b -> p.matcher(str(b.get("title"))).find()).limit(5).toList();
        }
        if (r.isEmpty()) {
            Pattern p = Pattern.compile(Pattern.quote(clean.replaceFirst("(?i)^(the|a|an)\\s+", "")), CI);
            r = books.stream().filter(b -> p.matcher(str(b.get("title"))).find()).limit(5).toList();
        }
        if (r.isEmpty()) {
            List<String> words = Arrays.stream(clean.toLowerCase(Locale.ROOT).split("\\s+")).filter(w -> w.length() > 2 && !STOP.contains(w)).toList();
            record Scored(Map<String, Object> book, long score) {
            }
            r = books.stream()
                .map(b -> {
                    String hay = (b.get("title") + " " + b.get("authorName") + " " + b.get("description")).toLowerCase(Locale.ROOT);
                    return new Scored(b, words.stream().filter(hay::contains).count());
                })
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingLong(Scored::score).reversed())
                .limit(3)
                .map(Scored::book)
                .toList();
        }
        return r;
    }

    private static Map<String, Object> reply(String message, Object... kv) {
        return obj("success", true, "data", Js.merge(obj("query", message), obj(kv)));
    }

    private List<Map<String, Object>> books(SearchResult r) {
        return r.rows().stream().map(Mappers::bookOut).map(AssistantService::aiBook).toList();
    }

    public Map<String, Object> query(CurrentUser user, Input body) {
        String message = Js.cut(body.text("message").strip(), 300);
        String lower = message.toLowerCase(Locale.ROOT);
        boolean staff = user.isStaff();
        Settings s = lib.settings();

        if (message.isEmpty() || HELP.matcher(lower).find()) {
            return reply(message, "intent", "help",
                "reply", "Hi! I'm your SmartLib assistant. I can locate books on the shelf, search by price, author, category, floor or language, list overdue books, show popular titles and summarise library statistics.",
                "suggestions", List.of("Where is The Alchemist?", "Show science books under ₹500", "Find available books by Chetan Bhagat", "Which books are overdue?",
                    "Show books available on Floor 2", "Most popular books"));
        }

        Matcher loc = LOCATE.matcher(message);
        if (loc.find()) {
            List<Map<String, Object>> found = titleSearch(loc.group(1));
            if (found.isEmpty()) {
                return reply(message, "intent", "location",
                    "reply", "I couldn't find a book matching \"" + loc.group(1).replace("?", "") + "\". Try the exact title or search by author.", "books", List.of());
            }
            Map<String, Object> b = found.get(0);
            String status;
            if (num(b.get("availableCopies")) > 0) status = "is available (" + b.get("availableCopies") + " of " + b.get("quantity") + " copies) on";
            else {
                Map<String, Object> next = db.row("SELECT due_date FROM loans WHERE book_id = ? AND status IN ('Issued','Overdue') ORDER BY due_date LIMIT 1", b.get("_id"));
                status = "is currently " + str(b.get("status")).toLowerCase(Locale.ROOT)
                    + (next != null ? " (next expected back " + JsDate.toDateString(JsDate.parse(next.get("due_date"))) + ")" : "") + ". Its shelf location is";
            }
            return reply(message, "intent", "location", "reply", b.get("title") + " " + status + " " + locationText(b.get("location")) + ".",
                "location", b.get("location"), "books", found.stream().map(AssistantService::aiBook).toList());
        }

        if (find("\\boverdue\\b|\\blate\\b.*\\bbooks?\\b", lower)) {
            boolean own = !staff || find("\\bmy\\b", lower);
            List<Map<String, Object>> rows = own
                ? db.rows(LOAN_SQL + " WHERE l.status = 'Overdue' AND l.member_id = ? ORDER BY l.due_date LIMIT 20", user.memberIdOrNone())
                : db.rows(LOAN_SQL + " WHERE l.status = 'Overdue'  ORDER BY l.due_date LIMIT 20");
            List<Map<String, Object>> list = new ArrayList<>();
            double total = 0;
            for (Map<String, Object> i : rows) {
                long d = JsDate.daysBetween(JsDate.parse(i.get("due_date")), JsDate.now());
                double fine = d * s.finePerDay();
                total += fine;
                list.add(Js.with(Mappers.issueOut(i), "daysOverdue", d, "fine", fine));
            }
            return reply(message, "intent", "overdue",
                "reply", list.isEmpty() ? "Great news — there are no overdue books right now."
                    : list.size() + " book" + (list.size() > 1 ? "s are" : " is") + " overdue, with " + money(total) + " in fines accruing at " + money(s.finePerDay()) + "/day.",
                "issues", list);
        }

        if (find("\\bmy\\s+(books|loans|issued)\\b", lower) && user.memberId() != null) {
            List<Map<String, Object>> rows = db.rows(LOAN_SQL + " WHERE l.member_id = ? AND l.status IN ('Issued','Overdue') ORDER BY l.due_date", user.memberId());
            return reply(message, "intent", "my-books",
                "reply", rows.isEmpty() ? "You don't have any books issued right now."
                    : "You have " + rows.size() + " book(s) issued. The next one is due on " + JsDate.toDateString(JsDate.parse(rows.get(0).get("due_date"))) + ".",
                "issues", rows.stream().map(Mappers::issueOut).toList());
        }

        if (find("\\b(my\\s+)?(fines?|dues|penalt)", lower) && (find("\\bmy\\b", lower) || !staff)) {
            if (user.memberId() == null) return reply(message, "intent", "fines", "reply", "Your account is not linked to a membership.", "fines", List.of());
            List<Map<String, Object>> fines = db.rows(FINE_SQL + " WHERE f.member_id = ? AND f.status IN ('Pending','Partially Paid')", user.memberId());
            double due = 0;
            for (Map<String, Object> f : fines) due += Mappers.fineRemaining(f);
            return reply(message, "intent", "fines",
                "reply", due > 0 ? "You have " + money(due) + " in pending fines across " + fines.size() + " charge(s)." : "You have no pending fines. 🎉",
                "fines", fines.stream().map(Mappers::fineOut).toList());
        }

        if (find("\\b(pending|outstanding|total)\\s+fines?\\b|\\bfines?\\s+(collected|pending|outstanding)", lower) && staff) {
            Map<String, Object> a = db.row("SELECT COALESCE(SUM(original_amount),0) t, COALESCE(SUM(discount),0) d, COALESCE(SUM(paid_amount),0) p, COALESCE(SUM(waived_amount),0) w FROM fines");
            double t = num(a.get("t")), d = num(a.get("d")), p = num(a.get("p")), w = num(a.get("w"));
            return reply(message, "intent", "stats", "reply",
                "Fines generated: " + money(t) + ". Collected: " + money(p) + ". Waived/discounted: " + money(w + d) + ". Outstanding: " + money(t - p - w - d) + ".");
        }

        if (find("\\bhow many\\b|\\bstatistic|\\bstats\\b|\\bsummary\\b|\\btotal books\\b", lower) && !find("\\bby\\b", lower)) {
            Map<String, Object> b = db.row("SELECT COALESCE(SUM(quantity),0) q, COUNT(*) t, COALESCE(SUM(available_copies),0) a, COALESCE(SUM(issued_copies),0) i FROM books");
            Object active = db.value("SELECT SUM(status = 'Active') a FROM members");
            Object overdue = db.value("SELECT COUNT(*) n FROM loans WHERE status = 'Overdue'");
            return reply(message, "intent", "stats", "reply",
                "The library has " + Js.numberToString(num(b.get("q"))) + " books (" + Js.numberToString(num(b.get("t"))) + " titles): "
                    + Js.numberToString(num(b.get("a"))) + " available and " + Js.numberToString(num(b.get("i"))) + " issued. "
                    + Js.numberToString(truthy(active) ? num(active) : 0) + " active members, " + Js.numberToString(num(overdue)) + " overdue loans.");
        }

        if (find("\\b(popular|most borrowed|trending|best|top)\\b", lower)) {
            SearchResult r = catalog.searchBooks(Input.of(Map.of("limit", 8)), "b.times_borrowed DESC");
            return reply(message, "intent", "books", "reply", "Here are the " + r.rows().size() + " most borrowed books in the library.",
                "books", books(r), "filters", obj("sort", "popular"));
        }
        if (find("\\b(new|latest|recent(ly)?)\\b.*\\b(books?|arrivals?|added)\\b|\\bnew arrivals\\b", lower)) {
            SearchResult r = catalog.searchBooks(Input.of(Map.of("limit", 8)), "b.created_at DESC");
            return reply(message, "intent", "books", "reply", "These are the most recently added books.", "books", books(r), "filters", obj("sort", "newest"));
        }
        if (find("\\brecommend|suggest", lower)) {
            SearchResult r = catalog.searchBooks(Input.of(Map.of("status", "available", "limit", 6)), "b.times_borrowed DESC");
            return reply(message, "intent", "books", "reply", "Based on what readers love right now, I recommend these (all available today):", "books", books(r));
        }

        Parsed parsed = parseQuery(message);
        if (parsed.understood().isEmpty()) {
            return reply(message, "intent", "unknown",
                "reply", "I'm not sure what you're looking for. Try asking where a book is, or search by author, category, price or floor.",
                "suggestions", List.of("Where is Wings of Fire?", "Programming books under ₹800", "Books by R.K. Narayan", "Books on Floor 3"));
        }
        SearchResult r = catalog.searchBooks(Input.of(Js.with(parsed.q(), "limit", 12)), "b.available_copies DESC, b.times_borrowed DESC");
        long total = ((Number) r.pagination().get("total")).longValue();
        String understood = String.join(", ", parsed.understood());
        return reply(message, "intent", "books",
            "reply", total > 0
                ? "I found " + total + " book" + (total > 1 ? "s" : "") + " matching " + understood + "." + (total > r.rows().size() ? " Showing the top " + r.rows().size() + "." : "")
                : "No books match " + understood + ". Try widening your search.",
            "books", books(r),
            "filters", parsed.q(),
            "total", total);
    }
}
