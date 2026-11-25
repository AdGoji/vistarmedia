# VistarMedia API client for Clojure

Clojure client implementation for [VistarMedia DSP
API](https://developers.vistarmedia.com/apis/dsp/get-started).

---
[![Clojars Project](https://img.shields.io/clojars/v/com.adgoji/vistarmedia.svg)](https://clojars.org/com.adgoji/vistarmedia)
[![cljdoc badge](https://cljdoc.org/badge/com.adgoji/vistarmedia)](https://cljdoc.org/d/com.adgoji/vistarmedia)

## Requirements

- Java 25+
- Clojure 1.13+

## Quick start

### Include the library to your project

See the [clojars](https://clojars.org/com.adgoji/vistarmedia) page for
the latest version.

### Add require

```clojure
(require '[com.adgoji.vistarmedia :as vistarmedia])
```

### Create a new client

```clojure
(def client
  (vistarmedia/new-client
   {::vistarmedia/credential {:email "your@email.com" :password "your-password"}}))
```

### Explore

The main entry point is `vistarmedia/invoke` function, which takes a
client instance and a parameters map, which should have at least
`::vistarmedia/op` key with a supported operation.

To see the list of available operations, inspect `vistarmedia/ops`
map.

Example:

```clojure
(vistarmedia/invoke (client)
                    {::vistarmedia/op ::vistarmedia/advertiser-get
                     ::vistarmedia/payload
                     {:id "123456"}})
;; => {:id "123456", :name "adgoji"}
```

See also documentation for `vistarmedia/invoke` for additional usage
information.

## Design decisions

The library is very minimal and basically a small wrapper on top of
hato HTTP client.  The following features are out of scope:
- request/response validation - it's a caller's responsibility to
  provide valid payloads and validate responses.
- pagination - paginated endpoints include `total-size` key, which can
  be used to calculate total list of pages to provide proper `page`
  query parameter.
- resilience - the `new-client` and `invoke` functions accept an
  optional `wrap-request` function, which can be used to wrap requests
  into resilience-providing functions.

## License

Released under the MIT license: https://opensource.org/license/mit
