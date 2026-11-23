import cheerio, { jinja2, jp, jpo, load } from "catvod:cheerio";
import { joinUrl } from "catvod";

function select(html, selectors) {
  const document = load(String(html ?? ""), undefined, false);
  let elements = document.root();
  for (const selector of selectors) {
    if (selector) elements = elements.find(selector);
  }
  return elements;
}

function pdfh(html, rule) {
  if (!rule) return "";
  const selectors = rule.split("&&").map(value => value.trim());
  const last = selectors[selectors.length - 1];
  const attribute = selectors.length > 1 || /^(Text|Html|OuterHtml)$/i.test(last)
    ? selectors.pop() : "Text";
  const element = select(html, selectors).first();
  if (/^text$/i.test(attribute)) return element.text().trim();
  if (/^html$/i.test(attribute)) return element.html() ?? "";
  if (/^outerhtml$/i.test(attribute)) return element.prop("outerHTML") ?? "";
  return element.attr(attribute) ?? "";
}

function pdfa(html, rule) {
  if (!rule) return [];
  const elements = select(html, rule.split("&&").map(value => value.trim()));
  return elements.map((index) => elements.eq(index).prop("outerHTML")).get();
}

function pd(html, rule, base) {
  const value = pdfh(html, rule);
  if (!base || !value) return value;
  try {
    return joinUrl(base, value);
  } catch (error) {
    return value;
  }
}

Object.assign(globalThis, { pdfh, pdfa, pd });
Object.assign(cheerio, { jinja2, jp, jpo, load });
export { jinja2, jp, jpo, load, pdfh, pdfa, pd };
export default cheerio;
