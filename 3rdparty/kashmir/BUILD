licenses(["notice"])

package(default_visibility = ["//visibility:public"])

cc_library(
    name = "kashmir-cxx",
    srcs = [
        "empty.cc",
        "abstractrandomstream.h",
        "iofwd.h",
        "iostate.h",
        "polydevrandom.h",
        "randomstream.h",
    ],
    hdrs = [ 
        "devrandom.h",
        "uuid.h",
    ],
    copts = [
        "-I3rdparty",
        "-I.",
    ],
    linkstatic = 1,
)
