#import "MinecraftOptionUtils.h"
#import "environ.h"

@interface MinecraftOptionUtils ()
@property(nonatomic) NSMutableArray<NSString *> *lineList;
- (void)synchronizeNewHorizonLowMemoryProfile:(BOOL)enabled
                                      gameDir:(NSString *)gameDir;
@end

@implementation MinecraftOptionUtils

+ (void)setupOptionsAtGameDir:(NSString *)gameDir
             lowMemoryProfile:(BOOL)lowMemoryProfile {
    NSAssert(windowWidth > 0 && windowHeight > 0, @"called before setting windowWidth/windowHeight?");
    MinecraftOptionUtils *options = [MinecraftOptionUtils sharedInstance];
    options.optionsPath = [gameDir stringByAppendingPathComponent:@"options.txt"];
    // initial gui scale, also implicitly calls load
    [options updateMCGuiScale];
    [options setKey:@"fullscreen" value:@"false"];
    [options setKey:@"overrideWidth" value:@(windowWidth)];
    [options setKey:@"overrideHeight" value:@(windowHeight)];
    // Default settings for performance
    [options setDefaultForKey:@"mipmapLevels" value:@"0"];
    [options setDefaultForKey:@"particles" value:@"1"];
    [options setDefaultForKey:@"renderDistance" value:@"2"];
    [options setDefaultForKey:@"simulationDistance" value:@"5"];
    [options synchronizeNewHorizonLowMemoryProfile:lowMemoryProfile
                                        gameDir:gameDir];
    [options save];
}

+ (instancetype)sharedInstance {
    static MinecraftOptionUtils *sharedInstance = nil;
    static dispatch_once_t onceToken;

    dispatch_once(&onceToken, ^{
        sharedInstance = [[MinecraftOptionUtils alloc] init];
    });

    return sharedInstance;
}

- (void)load {
    NSAssert(self.optionsPath.length, @"optionsPath is not set");
    self.lineList = [NSMutableArray array];

    NSError *error = nil;
    NSString *contents = [NSString stringWithContentsOfFile:self.optionsPath
                                                   encoding:NSUTF8StringEncoding
                                                      error:&error];

    if (error != nil) {
        NSLog(@"Could not load options.txt: %@", error);
        return;
    }

    self.lineList = [[contents componentsSeparatedByCharactersInSet:
                                  [NSCharacterSet newlineCharacterSet]] mutableCopy];
}

- (void)ensureLoaded {
    NSAssert(self.lineList != nil, @"Unitialized MinecraftOptionUtils");
}

- (void)setKey:(NSString *)key value:(NSString *)value {
    [self ensureLoaded];

    NSString *prefix = [key stringByAppendingString:@":"];

    for (NSUInteger i = 0; i < self.lineList.count; i++) {
        NSString *line = self.lineList[i];

        if ([line hasPrefix:prefix]) {
            self.lineList[i] = [NSString stringWithFormat:@"%@:%@", key, value];
            return;
        }
    }

    [self.lineList addObject:[NSString stringWithFormat:@"%@:%@", key, value]];
}

- (void)setDefaultForKey:(NSString *)key value:(NSString *)value {
    if ([self getValueForKey:key] == nil) {
        [self.lineList addObject:[NSString stringWithFormat:@"%@:%@", key, value]];
    }
}

- (nullable NSString *)getValueForKey:(NSString *)key {
    [self ensureLoaded];

    NSString *prefix = [key stringByAppendingString:@":"];

    for (NSString *line in self.lineList) {
        if ([line hasPrefix:prefix]) {
            NSRange range = [line rangeOfString:@":"];

            if (range.location != NSNotFound) {
                return [line substringFromIndex:range.location + 1];
            }
        }
    }

    return nil;
}

- (void)removeValueForKey:(NSString *)key {
    [self ensureLoaded];

    NSString *prefix = [key stringByAppendingString:@":"];

    NSIndexSet *indexes = [self.lineList indexesOfObjectsPassingTest:^BOOL(NSString *line, NSUInteger idx, BOOL *stop) {
        return [line hasPrefix:prefix];
    }];

    if (indexes.count > 0) {
        [self.lineList removeObjectsAtIndexes:indexes];
    }
}

- (void)synchronizeNewHorizonLowMemoryProfile:(BOOL)enabled
                                      gameDir:(NSString *)gameDir {
    NSDictionary<NSString *, NSString *> *targets = @{
        @"renderDistance": @"5",
        @"simulationDistance": @"5",
        @"entityDistanceScaling": @"0.5",
        @"mipmapLevels": @"0",
        @"biomeBlendRadius": @"0",
        @"graphicsMode": @"0",
        @"graphics": @"0",
        @"particles": @"2",
        @"clouds": @"false",
        @"cloudStatus": @"false",
        @"entityShadows": @"false",
    };
    NSSet<NSString *> *numericCaps = [NSSet setWithArray:@[
        @"renderDistance", @"simulationDistance", @"entityDistanceScaling",
        @"mipmapLevels", @"biomeBlendRadius",
    ]];
    NSString *snapshotPath = [gameDir stringByAppendingPathComponent:
        @"config/newhorizon-low-pressure-options.json"];
    NSData *snapshotData = [NSData dataWithContentsOfFile:snapshotPath];
    NSMutableDictionary *snapshot = snapshotData == nil ? nil :
        [NSJSONSerialization JSONObjectWithData:snapshotData
            options:NSJSONReadingMutableContainers error:nil];
    if (![snapshot isKindOfClass:NSMutableDictionary.class]) {
        snapshot = [@{@"active": @NO, @"options": @{}} mutableCopy];
    }
    BOOL wasActive = [snapshot[@"active"] boolValue];

    if (enabled) {
        if (!wasActive) {
            NSMutableDictionary *saved = [NSMutableDictionary new];
            for (NSString *key in targets) {
                saved[key] = [self getValueForKey:key] ?: NSNull.null;
            }
            snapshot[@"active"] = @YES;
            snapshot[@"options"] = saved;
            NSData *data = [NSJSONSerialization dataWithJSONObject:snapshot
                options:NSJSONWritingPrettyPrinted error:nil];
            [NSFileManager.defaultManager createDirectoryAtPath:
                snapshotPath.stringByDeletingLastPathComponent
                withIntermediateDirectories:YES attributes:nil error:nil];
            [data writeToFile:snapshotPath options:NSDataWritingAtomic error:nil];
        }
        for (NSString *key in targets) {
            NSString *target = targets[key];
            NSString *current = [self getValueForKey:key];
            if ([numericCaps containsObject:key] && current != nil) {
                NSScanner *scanner = [NSScanner scannerWithString:current];
                double currentValue = 0;
                if ([scanner scanDouble:&currentValue] && scanner.isAtEnd) {
                    target = [NSString stringWithFormat:@"%g",
                        MIN(currentValue, target.doubleValue)];
                }
            }
            [self setKey:key value:target];
        }
        NSLog(@"[NHLowPressureOptions] Applied compact Minecraft options");
    } else if (wasActive) {
        NSDictionary *saved = snapshot[@"options"];
        for (NSString *key in targets) {
            id value = saved[key];
            if (value == nil || value == NSNull.null) {
                [self removeValueForKey:key];
            } else {
                [self setKey:key value:value];
            }
        }
        snapshot[@"active"] = @NO;
        NSData *data = [NSJSONSerialization dataWithJSONObject:snapshot
            options:NSJSONWritingPrettyPrinted error:nil];
        [data writeToFile:snapshotPath options:NSDataWritingAtomic error:nil];
        NSLog(@"[NHLowPressureOptions] Restored player Minecraft options");
    }
}

- (void)updateMCGuiScale {
    [self load];
    guiScale = [self getValueForKey:@"guiScale"].intValue;
    //guiScale = (str == null ? 0 :Integer.parseInt(str));

    int scale = MAX(MIN(windowWidth / 320, windowHeight / 240), 1);
    if(scale < guiScale || guiScale == 0){
        guiScale = scale;
    }
}

- (void)save {
    [self ensureLoaded];

    if (self.optionsPath.length == 0) {
        NSLog(@"Could not save options.txt: optionsPath is not set");
        return;
    }

    NSString *result = [self.lineList componentsJoinedByString:@"\n"];

    NSError *error = nil;
    BOOL success = [result writeToFile:self.optionsPath
                            atomically:YES
                              encoding:NSUTF8StringEncoding
                                 error:&error];

    if (!success) {
        NSLog(@"Could not save options.txt: %@", error);
    }

    self.lineList = nil;
}

@end
