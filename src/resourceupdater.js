window.addEventListener('message', function(event) {
  var myblob = (document.location.href.indexOf("blob:" + event.origin) == 0);
  var parentsent = (window.parent.location.href.indexOf(event.origin) == 0);
  var reference = event.data["reference"];
  var kind = event.data["kind"];
  var url = event.data["url"];
  if (myblob && parentsent && reference && kind && url) {
    console.log("Slingcode live reloading tag:", event.origin, kind, reference, url);
    var selector = '[data-slingcode-reference="' + reference + '"]';
    var el = document.querySelector(selector);
    var boss = el.parentElement;
    if (kind == "link") {
      el.setAttribute("href", url);
    } else if (kind == "script") {
      var elnew = document.createElement("script");
      elnew.setAttribute("data-slingcode-reference", reference);
      elnew.setAttribute("src", url);
      boss.appendChild(elnew);
      boss.removeChild(el);
    }
  }
});
