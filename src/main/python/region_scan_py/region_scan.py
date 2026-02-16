import argparse
import itertools
import logging
from pathlib import Path

import tqdm

import mca


logger = logging.getLogger('RegionScanner')


def chunks_iterator(region: mca.Region):
    for x, z in itertools.product(range(32), repeat=2):
        try:
            chunk = region.get_chunk(x, z)
        except mca.region.ChunkNotFound:
            continue
        yield chunk


def main(world_dir: Path):
    for region_file in (world_dir / 'region').rglob('*.mca'):
        region_file: Path
        try:
            region = mca.Region.from_file(str(region_file))

            # convert `/path/r.x.z.mca` to (x, z)
            region_coord_x, region_coord_z = map(
                int,
                region_file.stem.replace('r.', '').split('.')
            )

            relative_rf = region_file.relative_to(region_file.parent.parent.parent)
            for chunk in tqdm.tqdm(chunks_iterator(region), desc=f"Scanning: {relative_rf}"):
                observer_list = [
                    (x, y, z) for block, (x, y, z) in chunk.stream_chunk()
                    if block.id == 'observer'
                ]
                if observer_list:
                    print(chunk.x, chunk.z, len(observer_list))
                    for c in observer_list:
                        print(*c)
        except Exception as e:
            logger.error('Caught error while scanning region %s', region_file, exc_info=e)


# Example
if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        prog='ObserversCounter',
        description='Scan all chunks files (regions) in world dir and count observers.',
        epilog='Output list of `x z obsCount`',
    )
    parser.add_argument(
        'world_dir',
        help='Path to directory where world is located. Example: `minecraft/world`, `minecraft/world_nether`'
    )

    args = parser.parse_args()
    main(Path(args.world_dir).absolute())