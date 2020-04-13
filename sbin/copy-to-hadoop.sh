#!/bin/bash
set -e
readonly LANGUAGE=en
readonly FILES=(article_categories labels page_links wikipedia_links skos_categories instance_types)
for FILE in ${FILES[@]}
do
  hadoop fs -copyFromLocal hdfs-data/${FILE}_${LANGUAGE}.ttl /${FILE}_${LANGUAGE}.nt
done