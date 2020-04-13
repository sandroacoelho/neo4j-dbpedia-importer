#!/bin/bash
set -e
readonly DBPEDIA_SITE=http://downloads.dbpedia.org
readonly DBPEDIA_VERSION=2016-10
readonly LANGUAGE=en
readonly FILES=(article_categories labels page_links wikipedia_links skos_categories instance_types)

for FILE in ${FILES[@]}
do
  wget ${DBPEDIA_SITE}/${DBPEDIA_VERSION}/core-i18n/${LANGUAGE}/${FILE}_${LANGUAGE}.ttl.bz2
  bzip2 -d ${FILE}_${LANGUAGE}.ttl.bz2
done