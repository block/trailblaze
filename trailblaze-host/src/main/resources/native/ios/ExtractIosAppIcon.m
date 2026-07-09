// Decodes an app's launcher icon out of a compiled asset catalog (`Assets.car`) using the private
// CoreUI.framework — the same framework Xcode/Simulator.app/Finder use internally to render
// `.car` renditions, so it stays correct across whatever pixel-compression scheme (raw ARGB, RLE,
// LZVN "deepmap", HEIF) a given Xcode toolchain used to produce the catalog. Prior-art tools that
// extract `.car` assets on macOS (acextract, cartool, AssetCatalogTinkerer) all take this route
// rather than hand-rolling the codecs, for the same reason.
//
// `CUICatalog`/`CUINamedImage` ship in /System/Library/PrivateFrameworks/CoreUI.framework with no
// public headers, so the `@interface` declarations below exist only to give clang enough
// signature information to type-check the call — the real implementation resolves at runtime via
// NSClassFromString against the framework linked in on the command line.
//
// Ships as a classpath resource (this file) rather than a standalone script — see
// IosAppIconExtractor.kt, which extracts this source to the workspace's native-tool cache and
// compiles it with `clang` on first use, so icon extraction works for any end user of the
// packaged `trailblaze` binary, not just a source checkout of this repo.
//
// Usage: ExtractIosAppIcon <path/to/Assets.car> <icon-name, e.g. AppIcon> <out.png>

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

@interface CUICatalogDecl : NSObject
- (instancetype)initWithURL:(NSURL *)url error:(NSError **)error;
- (NSArray *)imagesWithName:(NSString *)name;
@end

@interface CUINamedImageDecl : NSObject
- (CGImageRef)image;
@property(readonly) CGSize size;
@end

int main(int argc, const char *argv[]) {
  @autoreleasepool {
    if (argc < 4) {
      fprintf(stderr, "usage: %s <car-path> <icon-name> <out.png>\n", argv[0]);
      return 2;
    }
    NSString *carPath = [NSString stringWithUTF8String:argv[1]];
    NSString *iconName = [NSString stringWithUTF8String:argv[2]];
    NSString *outPath = [NSString stringWithUTF8String:argv[3]];

    Class catalogClass = NSClassFromString(@"CUICatalog");
    if (!catalogClass) {
      fprintf(stderr, "error: CUICatalog not found — is CoreUI.framework linked?\n");
      return 1;
    }

    NSError *error = nil;
    CUICatalogDecl *catalog =
        [[catalogClass alloc] initWithURL:[NSURL fileURLWithPath:carPath] error:&error];
    if (!catalog) {
      fprintf(stderr, "error: failed to open catalog: %s\n", error.localizedDescription.UTF8String);
      return 1;
    }

    // -imagesWithName: returns every rendition matching this name across idioms/scales/sizes —
    // for a standard AppIcon set this includes the 1024x1024 App Store marketing rendition, which
    // we prefer since it's the highest-fidelity source available. Skip
    // CUINamedMultisizeImageSet entries (a container the catalog also returns), keeping only
    // decoded CUINamedImage instances.
    Class namedImageClass = NSClassFromString(@"CUINamedImage");
    CUINamedImageDecl *best = nil;
    CGFloat bestArea = -1;
    for (id candidate in [catalog imagesWithName:iconName]) {
      if (![candidate isKindOfClass:namedImageClass]) continue;
      CUINamedImageDecl *img = (CUINamedImageDecl *)candidate;
      CGFloat area = img.size.width * img.size.height;
      if (area > bestArea) {
        bestArea = area;
        best = img;
      }
    }
    if (!best) {
      fprintf(stderr, "error: no rendition named '%s' in %s\n", iconName.UTF8String, carPath.UTF8String);
      return 1;
    }

    CGImageRef cgImage = best.image;
    if (!cgImage) {
      fprintf(stderr, "error: rendition '%s' decoded to no image\n", iconName.UTF8String);
      return 1;
    }

    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithCGImage:cgImage];
    NSData *pngData = [rep representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
    if (![pngData writeToFile:outPath atomically:YES]) {
      fprintf(stderr, "error: failed to write %s\n", outPath.UTF8String);
      return 1;
    }
    return 0;
  }
}
