#import "NewHorizonClient.h"

NSString *const NHProductionVersionID = @"1.20.1-forge-47.4.0";
NSString *const NHProductionForgeInstallerVersion = @"1.20.1-47.4.0";

BOOL NHProductionVersionIsInstalled(void) {
    NSString *gameDirectory = @(getenv("POJAV_GAME_DIR") ?: "");
    if (gameDirectory.length == 0) return NO;
    NSString *relativePath = [NSString stringWithFormat:
        @"versions/%1$@/%1$@.json", NHProductionVersionID];
    return [NSFileManager.defaultManager fileExistsAtPath:
        [gameDirectory stringByAppendingPathComponent:relativePath]];
}

void NHRemoveObsoleteClientMarkers(void) {
    NSString *gameDirectory = @(getenv("POJAV_GAME_DIR") ?: "");
    if (gameDirectory.length == 0) return;
    NSFileManager *fileManager = NSFileManager.defaultManager;
    for (NSString *marker in @[@".newhorizon-lite",
                                @".newhorizon-vanilla-baseline"]) {
        NSString *path = [gameDirectory stringByAppendingPathComponent:marker];
        if ([fileManager fileExistsAtPath:path]) {
            NSError *error;
            if (![fileManager removeItemAtPath:path error:&error]) {
                NSLog(@"[NHForgeBootstrap] Could not remove obsolete marker %@: %@",
                    marker, error.localizedDescription);
            }
        }
    }
    NSString *obsoleteVersion = [gameDirectory stringByAppendingPathComponent:
        @"versions/1.20.1-newhorizon-lite"];
    if ([fileManager fileExistsAtPath:obsoleteVersion]) {
        NSError *error;
        if (![fileManager removeItemAtPath:obsoleteVersion error:&error]) {
            NSLog(@"[NHForgeBootstrap] Could not remove obsolete version %@: %@",
                obsoleteVersion, error.localizedDescription);
        }
    }
}
