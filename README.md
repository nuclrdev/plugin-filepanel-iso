# ISO File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin for browsing disk-image
files without mounting them through the operating system. Open an `.iso` from a
normal file panel and navigate its directories in-place, preview supported
files, or extract selected files and directories to the opposite local panel.

## Features

- Read-only ISO browsing with normal parent/directory navigation
- ISO 9660, enhanced/Joliet names, Rock Ridge, and UDF (up to 2.60)
- File size and modification-time metadata where the image provides it
- Stream-based previews and extraction; file payloads are never loaded wholly
  into memory
- Recursive and multi-selection extraction with byte progress and cancellation
- Existing destination items prompt for overwrite, skip, keep-both, or cancel
- Temporary materialisation of only a nested/remote ISO image when it has no
  local backing path
- ISO file handles and temporary files are released when the panel closes

The panel deliberately exposes no edit, rename, move, delete, make-directory,
paste, or other mutation command. The source image is never changed.

## Usage

Open an `.iso` from a filesystem panel. The location bar uses the virtual-path
form `image.iso:/path/inside`. Use F3 for the normal Nuclr viewer and F5 to
extract the current selection into the opposite local filesystem panel.

## Implementation and dependency

The plugin follows the same `FilePanelNuclrPlugin` navigation and transfer
conventions as `filepanel-zip`, with a read-only adapter in place of an NIO ZIP
mount. It uses
[`com.palantir.isofilereader:isofilereader:1.4.0`](https://github.com/palantir/isofilereader),
an Apache-2.0, pure-Java reader with no runtime transitive dependencies. The
reader indexes directory metadata when the image opens, but file data remains
lazy and is read through bounded `InputStream`s.

## Known limitations

- UDF support in the upstream reader is less extensively tested than ISO 9660.
- UDF payload streaming is limited to files held in one recorded `short_ad`
  extent. Embedded ICB data, `long_ad`/`ext_ad` layouts, sparse extents, and
  fragmented or multi-extent files remain visible but are marked unreadable;
  the plugin refuses to extract them rather than risk returning corrupt bytes.
- Interleaved and multi-extent ISO 9660 file records are likewise left visible
  but unreadable because the upstream reader exposes only one contiguous extent.
- Images using non-standard logical block sizes, damaged allocation metadata,
  unsupported UDF file-entry types, or filesystems other than ISO 9660/UDF are
  rejected with a user-facing error.
- Extraction currently targets a local filesystem panel. Writing into another
  virtual/remote panel requires that panel to provide a stream-copy contract in
  the Nuclr SDK.
- The ISO metadata tree is indexed once at open time because the upstream UDF
  reader does not expose directory-at-a-time traversal. File contents are not
  scanned during indexing.

## Build

Requires Java 25 and Maven:

```text
mvn test
mvn package
```

The packaged plugin ZIP is written under `target/`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
