#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSString *const NHProductionVersionID;
FOUNDATION_EXPORT NSString *const NHProductionForgeInstallerVersion;

/** Returns YES only when the pinned Forge profile has a complete version JSON. */
BOOL NHProductionVersionIsInstalled(void);

/** Removes obsolete diagnostic launch markers from older New Horizon builds. */
void NHRemoveObsoleteClientMarkers(void);

NS_ASSUME_NONNULL_END
