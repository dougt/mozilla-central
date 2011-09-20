
const Cc = Components.classes;
const Ci = Components.interfaces;
const Cu = Components.utils;

Cu.import("resource://gre/modules/XPCOMUtils.jsm");
Cu.import("resource://gre/modules/Services.jsm");

function resolveURIInternal(aCmdLine, aArgument) {
  try {
      let uri = aCmdLine.resolveURI(aArgument);
      let urifixup = Cc["@mozilla.org/docshell/urifixup;1"].getService(Ci.nsIURIFixup);
      uri = urifixup.createFixupURI(aArgument, 1);
  } catch (e) {
  }
  return uri;
}

function BrowserCLH() { }

BrowserCLH.prototype = {

  handle: function fs_handle(aCmdLine) {
      try {
          let urlParam = aCmdLine.handleFlagWithParam("remote", false);
          if (urlParam) {
              // TODO:
              // browserWin.browserDOMWindow will be null if
              // the chrome page hasn't loaded yet.  We
              // should test for this, and reschedule the
              // event.

              let uri = resolveURIInternal(aCmdLine, urlParam);
              let browserWin = Services.wm.getMostRecentWindow("navigator:browser");
              browserWin.browserDOMWindow.openURI(uri,
                                                  null,
                                                  Ci.nsIBrowserDOMWindow.OPEN_CURRENTWINDOW,
                                                  Ci.nsIBrowserDOMWindow.OPEN_EXTERNAL);
              aCmdLine.preventDefault = true;
          }
      } catch (x) {
          Cc["@mozilla.org/consoleservice;1"]
              .getService(Ci.nsIConsoleService)
              .logStringMessage("fs_handle exception!:  " + x);
      }
  },

  // QI
  QueryInterface: XPCOMUtils.generateQI([Ci.nsICommandLineHandler]),

  // XPCOMUtils factory
  classID: Components.ID("{be623d20-d305-11de-8a39-0800200c9a66}"),
};

var components = [ BrowserCLH ];
const NSGetFactory = XPCOMUtils.generateNSGetFactory(components);
