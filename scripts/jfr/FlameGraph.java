import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Gráfico de chamas (SVG) de uma gravação JFR, sem ferramentas externas. Uso:
 *   java scripts/jfr/FlameGraph.java GRAVACAO.jfr SAIDA.svg [FILTRO] [TITULO]
 *
 * Cada pilha pesa em milissegundos: amostra de CPU (jdk.ExecutionSample, o período da gravação) mais
 * leitura de socket (jdk.SocketRead, a duração do evento: o tempo esperando o Postgres, que uma thread
 * virtual desmontada não mostra em amostra de CPU). Com FILTRO, só entram pilhas que contêm o texto
 * (por exemplo "JooqSearchReadModel"). O resumo sai na saída padrão.
 */
public final class FlameGraph {

  private static final class Node {
    final String name;
    double weight;
    final Map<String, Node> children = new LinkedHashMap<>();

    Node(String name) {
      this.name = name;
    }
  }

  private static final int WIDTH = 1400;
  private static final int ROW = 17;

  public static void main(String[] args) throws IOException {
    var in = Path.of(args[0]);
    var out = Path.of(args[1]);
    var filter = args.length > 2 ? args[2] : "";
    var title = args.length > 3 ? args[3] : in.getFileName().toString();
    var root = new Node("all");
    double cpu = 0;
    double wait = 0;
    double periodMs = 10;
    try (var file = new RecordingFile(in)) {
      while (file.hasMoreEvents()) {
        var event = file.readEvent();
        var type = event.getEventType().getName();
        double ms;
        if (type.equals("jdk.ExecutionSample")) {
          ms = periodMs;
        } else if (type.equals("jdk.SocketRead")) {
          ms = event.getDuration().toNanos() / 1e6;
        } else {
          continue;
        }
        var names = stack(event);
        if (names.isEmpty() || !String.join(";", names).contains(filter)) {
          continue;
        }
        if (type.equals("jdk.SocketRead")) {
          names.add("[JDBC wait: socket read]");
          wait += ms;
        } else {
          cpu += ms;
        }
        var node = root;
        node.weight += ms;
        for (var name : names) {
          node = node.children.computeIfAbsent(name, Node::new);
          node.weight += ms;
        }
      }
    }
    System.out.printf(
        "%s: filtro '%s': CPU Java %.0f ms, espera de socket (JDBC) %.0f ms, total %.0f ms%n",
        in.getFileName(), filter, cpu, wait, cpu + wait);
    var lines = new ArrayList<String>();
    var depth = draw(root, 0, 0, WIDTH, lines);
    try (var w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      var height = (depth + 2) * ROW + 24;
      w.printf(
          "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\""
              + " font-family=\"monospace\" font-size=\"11\">%n",
          WIDTH, height, WIDTH, height);
      w.printf("<rect width=\"100%%\" height=\"100%%\" fill=\"#fff\"/>%n");
      w.printf(
          "<text x=\"6\" y=\"15\" font-size=\"13\" fill=\"#111\">%s (CPU %.0f ms, espera JDBC %.0f ms)</text>%n",
          escape(title), cpu, wait);
      lines.forEach(w::println);
      w.println("</svg>");
    }
  }

  private static ArrayList<String> stack(RecordedEvent event) {
    var names = new ArrayList<String>();
    var trace = event.getStackTrace();
    if (trace == null) {
      return names;
    }
    var frames = trace.getFrames();
    for (var i = frames.size() - 1; i >= 0; i--) {
      RecordedFrame f = frames.get(i);
      var type = f.getMethod().getType().getName();
      names.add(type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName());
    }
    return names;
  }

  /** Icicle layout: the root on top, children below it, widths proportional to the weight. */
  private static int draw(Node node, int level, double x, double width, ArrayList<String> out) {
    var y = 24 + level * ROW;
    var share = String.format("%.0f ms", node.weight);
    out.add(
        String.format(
            "<g><title>%s (%s)</title><rect x=\"%.2f\" y=\"%d\" width=\"%.2f\" height=\"%d\" fill=\"%s\""
                + " stroke=\"#fff\" stroke-width=\"0.5\"/>%s</g>",
            escape(node.name),
            share,
            x,
            y,
            width,
            ROW - 1,
            color(node.name),
            width > 40 ? label(node.name, x, y, width) : ""));
    var depth = level;
    var cursor = x;
    for (var child : node.children.values()) {
      var w = width * child.weight / node.weight;
      if (w >= 0.4) {
        depth = Math.max(depth, draw(child, level + 1, cursor, w, out));
      }
      cursor += w;
    }
    return depth;
  }

  private static String label(String name, double x, int y, double width) {
    var chars = (int) (width / 6.6);
    var text = name.length() > chars ? name.substring(0, Math.max(chars - 1, 1)) + "~" : name;
    return String.format(
        "<text x=\"%.2f\" y=\"%d\" fill=\"#111\">%s</text>", x + 2, y + ROW - 5, escape(text));
  }

  private static String color(String name) {
    if (name.startsWith("[JDBC")) {
      return "#6aa6e8";
    }
    var h = Math.abs(name.hashCode());
    return String.format("rgb(%d,%d,%d)", 225 + h % 30, 120 + h / 7 % 90, 40 + h / 31 % 50);
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
