package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.Uf;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The search page and the list fragment htmx swaps into it. Both read the same use case as the REST
 * API; the page also renders the list itself, so a pushed address works when opened or reloaded.
 */
@Controller
class SearchPages {

  private static final String RESULTS = "/fragments/results";

  /** The form as the browser sent it; the empty select values are the same as no filter. */
  record Form(String q, String uf, Integer modality, boolean open) {

    /** NUL and the other control characters match nothing and some break the SQL driver. */
    SearchQuery query(Cursor cursor) {
      if (q != null && q.strip().length() > SearchQuery.MAX_TEXT) {
        throw new InvalidInput(
            "O texto da busca deve ter no máximo " + SearchQuery.MAX_TEXT + " caracteres.");
      }
      if (q != null && q.chars().anyMatch(Character::isISOControl)) {
        throw new InvalidInput("O texto da busca não pode ter caracteres de controle.");
      }
      var filters = new Filters(unit(), null, modality, null, null, open, null);
      return new SearchQuery(q, filters, cursor);
    }

    private Uf unit() {
      try {
        return uf == null || uf.isBlank() ? null : Uf.of(uf);
      } catch (IllegalArgumentException e) {
        throw new InvalidInput("UF inválida.");
      }
    }

    /** The same form as a path with a query, every value encoded and the empty ones left out. */
    String url(String path, String cursor) {
      var params = new LinkedHashMap<String, String>();
      put(params, "q", q);
      put(params, "uf", uf);
      put(params, "modality", modality == null ? null : modality.toString());
      put(params, "open", open ? "true" : null);
      put(params, "cursor", cursor);
      var url = UriComponentsBuilder.fromPath(path);
      params.keySet().forEach(name -> url.queryParam(name, "{" + name + "}"));
      return url.encode().buildAndExpand(params).toUriString();
    }

    private static void put(Map<String, String> params, String name, String value) {
      if (value != null && !value.isBlank()) {
        params.put(name, value);
      }
    }
  }

  private final SearchUseCase search;
  private final Clock clock;

  SearchPages(SearchUseCase search, Clock clock) {
    this.search = search;
    this.clock = clock;
  }

  @GetMapping("/")
  String page(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String uf,
      @RequestParam(required = false) Integer modality,
      @RequestParam(defaultValue = "false") boolean open,
      @RequestParam(required = false) String cursor,
      Model model) {
    list(new Form(q, uf, modality, open), cursor, model);
    model.addAttribute("ufs", Uf.values());
    model.addAttribute("modalities", Labels.MODALITIES);
    return "search/page";
  }

  /**
   * Without a cursor it is a new search: the whole list, and the address of the full page for htmx
   * to push (the address of this fragment would show a bare list when reloaded). With a cursor it
   * is the next page: only its items, and the new button in place of the old one.
   */
  @GetMapping(RESULTS)
  String results(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String uf,
      @RequestParam(required = false) Integer modality,
      @RequestParam(defaultValue = "false") boolean open,
      @RequestParam(required = false) String cursor,
      @RequestHeader(name = "HX-Trigger", required = false) String trigger,
      Model model,
      HttpServletResponse response) {
    var form = new Form(q, uf, modality, open);
    list(form, cursor, model);
    if (cursor != null) {
      return "search/results :: items";
    }
    // typing replaces the entry, so each keystroke batch does not fill the history
    response.setHeader("q".equals(trigger) ? "HX-Replace-Url" : "HX-Push-Url", form.url("/", null));
    return "search/results :: results";
  }

  private void list(Form form, String cursor, Model model) {
    var page = search.search(form.query(cursor == null ? null : Cursor.decode(cursor)));
    var now = clock.instant();
    model.addAttribute("form", form);
    model.addAttribute("rows", page.items().stream().map(hit -> ResultRow.of(hit, now)).toList());
    var next = page.nextCursor() == null ? null : page.nextCursor().encode();
    model.addAttribute("moreUrl", next == null ? null : form.url(RESULTS, next));
    model.addAttribute("moreHref", next == null ? null : form.url("/", next));
  }
}
