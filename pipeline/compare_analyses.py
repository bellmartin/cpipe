#!/usr/bin/env python
###########################################################################
#
# This file is part of Cpipe.
#
# Cpipe is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, under version 3 of the License, subject
# to additional terms compatible with the GNU General Public License version 3,
# specified in the LICENSE file that is part of the Cpipe distribution.
#
# Cpipe is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with Cpipe.  If not, see <http:#www.gnu.org/licenses/>.
#
###########################################################################
#
###########################################################################

import argparse
import csv
import glob
import sys

def find_variants( fh ):
  csvfh = csv.reader( fh, delimiter=',', quotechar='"' )
  indexes = None
  result = set()
  for line in csvfh:
    if not indexes:
      indexes = [ line.index(x) for x in ('Gene','Chr','Start') ]
    else:
      key = '\t'.join( [ line[i] for i in indexes ] )
      result.add( key )
  
def compare( d1, d2, s1, s2, out ):
  # compare the annovars
  a1fn = glob.glob( '{0}/analysis/results/*{1}.annovarx.csv'.format( d1, s1 ) )[0]
  a2fn = glob.glob( '{0}/analysis/results/*{1}.annovarx.csv'.format( d2, s2 ) )[0]
  a1 = find_variants( open( a1fn, 'r' ) )
  a2 = find_variants( open( a2fn, 'r' ) )
  # common
  both = a1.intersection(a2)
  out.write( '----- {0} variants in common -----\n'.format( both ) )
  for x in sorted( list( both ) ):
    out.write( '{0}\n'.format( x ) )
  # only s1
  s1only = s1.difference( s2 )
  out.write( '----- {0} variants only in {1} -----\n'.format( s1only, s1 ) )
  for x in sorted( list( s1only ) ):
    out.write( '{0}\n'.format( x ) )
  # only s2
  s2only = s2.difference( s1 )
  out.write( '----- {0} variants only in {1} -----\n'.format( s2only, s2 ) )
  for x in sorted( list( s2only ) ):
    out.write( '{0}\n'.format( x ) )

if __name__ == '__main__':
  parser = argparse.ArgumentParser(description='Compare two analyses')
  parser.add_argument('--dir1', required=True, help='batch 1 directory')
  parser.add_argument('--dir2', required=True, help='batch 2 directory')
  parser.add_argument('--sample1', required=True, help='sample 1 name')
  parser.add_argument('--sample2', required=True, help='sample 2 name')
  args = parser.parse_args()
  compare( args.dir1, args.dir2, args.sample1, args.sample2, sys.stdout )
