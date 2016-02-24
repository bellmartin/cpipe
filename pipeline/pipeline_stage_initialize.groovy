/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Cpipe.
// 
// Cpipe is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Cpipe distribution.
// 
// Cpipe is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Cpipe.  If not, see <http://www.gnu.org/licenses/>.
// 
/////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////
// stages
///////////////////////////////////////////////////////////////////

// remove spaces from gene lists and point to a new sample metadata file
// note that this isn't run through bpipe
correct_sample_metadata_file = {
    def target = new File( 'results' )
    if( !target.exists() ) {
        target.mkdirs()
    }
    [ "sh", "-c", "python $SCRIPTS/correct_sample_metadata_file.py < $it > results/samples.corrected" ].execute().waitFor()
    return "results/samples.corrected"
}

check_sample_info = {

    doc "Validate basic sample information is correct"

    def missingSummary = []
    for(sample in samples) {

        // Check that FASTQ files begin with the sample name followed by underscore
        def files = sample_info[sample].files.fastq
        if(files.any { !file(it).name.startsWith(sample+"_")}) {
            files.each { println "FASTQ: $it | sample=$sample" }
            fail report('templates/invalid_input.html') to channel: cpipe_operator, 
                                                           subject: "FASTQ files for sample $sample have invalid file name format", 
                                                           message: "Files $files do not start with the sample name $sample" 
        }

        // Check that all the files specified for the sample exist
        def missingFiles = files.grep { !file(it).exists() }
        if(missingFiles) 
            missingSummary << """
                    The following files specified for sample $sample could not be found:\n\n${missingFiles*.center(120).join('\n')}

                    Please check that the files in your sample file really exist in the data directory.
                 """.stripIndent()

        // Check that file names contain the lane information
        def missingLanes = files.grep { !(it ==~ ".*_L[0-9]*_.*") }
        if(missingLanes) 
            missingSummary << """
                    The following files specified for sample $sample do not contain lane information:\n\n${missingLanes*.center(120).join('\n')}

                    FASTQ file names are required to contain lane identifiers such as L001, L1 or similar. 
                    Please check your input FASTQ and rename it if necessary.
            """

        // Check that file names contain the read number information
        def missingRP = files.grep { !(it ==~ ".*_R[0-9][_.].*fastq.gz\$") }
        if(missingRP) 
            missingSummary << """
                    The following files for sample $sample do not contain the read number in the expected format:\n\n${missingRP*.center(120).join('\n')}

                    FASTQ file names are required to contain the number of the read from the read pair (1 or 2) 
                    in the form '_R1_' or '_R1.'. Please check your input FASTQ and rename it if necessary.
            """
    }

    if(missingSummary) {
        fail missingSummary.join("\n" + ("-" * 120) + "\n")
    }
}

check_tools = {
    doc """
        Checks for presence of optional tools and sets appropriate pipeline variables
        to enable or disable corresponding pipeline features
        """

    var UPDATE_VARIANT_DB : VARIANT_DB,
        ANNOTATION_VARIANT_DB : VARIANT_DB

    produce("revision.txt") {
        exec """
            git describe --always > $output.txt || true
        """
    }

    if(file(GROOVY_NGS).name in ["1.0.1","1.0"])
        fail "This version of Cpipe requires GROOVY_NGS >= 1.0.2. Please edit config.groovy to set the latest version of tools/groovy-ngs-utils"

    branch.UPDATE_VARIANT_DB = UPDATE_VARIANT_DB
    branch.ANNOTATION_VARIANT_DB = ANNOTATION_VARIANT_DB
}

update_gene_lists = {
    doc "find additionally specified genes and add new ones that aren't on the incidentalome, to the gene lists"

    // builds additional genes from sample metadata file, then adds any new ones to the flagship
    // creates files: ../design/cohort.add.genes.txt, cohort.addonce.sample.genes.txt, cohort.notfound.genes.txt
    exec """
        mkdir -p "../design"

        python $SCRIPTS/find_new_genes.py --reference "$BASE/designs/genelists/exons.bed" --exclude "$BASE/designs/genelists/incidentalome.genes.txt" --target ../design < $sample_metadata_file

        python $SCRIPTS/update_gene_lists.py --source ../design --target "$BASE/designs" --log "$BASE/designs/genelists/changes.genes.log"
    """
}

create_combined_target = {

    // Construct the region for variant calling from 
    //
    //   a) all of the disease cohort BED files
    //   b) the EXOME target regions
    //   c) any additional genes being analyzed
    //
    // This way we avoid calling variants over the entire genome, but still
    // include everything of interest
    String diseaseGeneLists = ANALYSIS_PROFILES.collect { "$BASE/designs/${it}/${it}.genes.txt" }.join(",")

    output.dir = "../design"

    produce("combined_target_regions.bed") {
        exec """
            { python $SCRIPTS/genelist_to_bed.py $diseaseGeneLists ../design/*.addonce.*.genes.txt < $BASE/designs/genelists/exons.bed; cat $EXOME_TARGET; } |
                cut -f 1,2,3 | 
                $BEDTOOLS/bin/bedtools sort | 
                $BEDTOOLS/bin/bedtools merge > $output.bed
        """

        branch.COMBINED_TARGET = output.bed
    }
}

create_synonymous_target = {
    doc "find regions that allow synonymous variants"

    output.dir = "../design"
    produce( "combined_synonymous_regions.bed" ) {
        def safe_tmp_dir = [TMPDIR, UUID.randomUUID().toString()].join( File.separator )

        exec """
            mkdir -p "$safe_tmp_dir"

            $BEDTOOLS/bin/bedtools slop -i $input.bed -g $HG19_CHROM_INFO -b $ALLOW_SYNONYMOUS_INTRON > "$safe_tmp_dir/intron.bed"

            $BEDTOOLS/bin/bedtools slop -i $input.bed -g $HG19_CHROM_INFO -b -$ALLOW_SYNONYMOUS_EXON | python $SCRIPTS/filter_bed.py > "$safe_tmp_dir/exon.bed"

            $BEDTOOLS/bin/bedtools subtract -a "$safe_tmp_dir/intron.bed" -b "$safe_tmp_dir/exon.bed" > $output.bed

            rm -r "$safe_tmp_dir"
        """

        branch.COMBINED_SYNONYMOUS = output.bed
    }
}

build_capture_stats = {
    output.dir = "qc"
    produce( "exon_coverage_stats.txt" ) {
        exec """
            python $SCRIPTS/calculate_exon_coverage.py --capture $EXOME_TARGET --exons $BASE/designs/genelists/exons.bed > qc/exon_coverage_stats.txt
        """
    }
}

generate_pipeline_id = {
    doc "Generate a pipeline run ID for this batch"
    output.dir="results"
    produce("run_id") {
      exec """
        python $SCRIPTS/update_pipeline_run_id.py --id $ID_FILE --increment True > $output
      """
    }
   // This line is necessary on some distributed file systems (e.g. MCRI) to ensure that
   // files get synced between nodes
   file("results").listFiles()
   run_id = new File('results/run_id').text.trim()
}

set_target_info = {

    doc "Validate and set information about the target region to be processed"

    var HG19_CHROM_INFO : false

    branch.splice_region_window=false
    branch.splice_region_bed_flag=""
    branch.multi_annovar=false

    branch.batch = batch
    branch.target_name = branch.name
    branch.target_bed_file = "../design/${target_name}.bed"
    branch.target_gene_file = "../design/${target_name}.genes.txt"
    branch.target_samples = sample_info.grep { it.value.target == target_name }*.value*.sample
    branch.transcripts_file = "../design/${target_name}.transcripts.txt"
    branch.target_config = "../design/${target_name}.settings.txt"

    println "Checking for target gene file: $target_gene_file"
    produce(target_gene_file) {
        exec """
            cp $BASE/designs/$target_name/${target_name}.genes.txt $target_gene_file;
        """
    }

    println "Checking for target bed file: $target_bed_file"
    produce(target_bed_file) {
        exec """
            if [ -e $BASE/designs/$target_name/${target_name}.bed ];
            then
                cp $BASE/designs/$target_name/${target_name}.bed $target_bed_file; 
            else
                python $SCRIPTS/genelist_to_bed.py $target_gene_file ../design/${target_name}.addonce.*.genes.txt < $BASE/designs/genelists/exons.bed > $target_bed_file;
            fi
        """
    }

    produce(transcripts_file) {
        exec """
            cp $BASE/designs/$target_name/${target_name}.transcripts.txt $transcripts_file;
        """
    }

    produce(target_config) {
        exec """
            if [ -e $BASE/designs/$target_name/${target_name}.settings.txt ];
            then
                cp $BASE/designs/$target_name/${target_name}.settings.txt $output.txt;
            else
                touch $output.txt;
            fi
        """
    }

    exec """
        if [ -e $BASE/designs/$target_name/${target_name}.pgx.vcf ] && [ ! -e ../designs/${target_name}.pgx.vcf ];
        then
            cp $BASE/designs/$target_name/${target_name}.pgx.vcf ../designs;
        fi
    """

    // Load arbitrary settings related to the target
    println "Loading settings for target region $branch.name from ${file(target_config).absolutePath}"
    load file(target_config).absolutePath
    if(multi_annovar) {
        println "Enabling multiple Annovar annotation sources for $target_name"
        branch.annoar = multiple_annovar 
    }
    
    println "Target $target_name is processing samples $target_samples"
}

create_splice_site_bed = {

    // If no splice region window is defined, simply set the 
    if(!splice_region_window) {
        branch.splice_region_bed_flag = ""
        return
    }

    msg "Setting regions for calling / annotation of splice variants to $splice_region_window bp past exon boundary"

    output.dir="../design"
    produce(target_name + ".splice.bed", target_name + ".exons.bed") {
        exec """
            python $SCRIPTS/create_exon_bed.py  
                -c -s $target_bed_file $ANNOVAR_DB/hg19_refGene.txt $transcripts_file -
              | $BEDTOOLS/bin/bedtools slop -g $HG19_CHROM_INFO -b $splice_region_window -i - > $output.bed

            python $SCRIPTS/create_exon_bed.py  
                -c $target_bed_file $ANNOVAR_DB/hg19_refGene.txt $transcripts_file $output2.bed
        """

        branch.splice_region_bed_flag = "-L $output1.bed"
        branch.exon_bed_file = output2.bed
    }

    println "Splice region flat = $splice_region_bed_flag"
    println "Exon bed file = $exon_bed_file"
}

sample_similarity_report = {

    doc "Create a report indicating the difference in count of variants for each combination of samples"

    output.dir = "qc"

    produce("similarity_report.txt") {
        exec """
            $JAVA -Xmx4g -cp $GROOVY_HOME/embeddable/groovy-all-2.3.4.jar:$GROOVY_NGS/groovy-ngs-utils.jar VCFSimilarity $inputs.vcf > $output.txt
             """
    }
}

validate_batch = {
    doc "Validates batch results"
    String diseaseGeneLists = ANALYSIS_PROFILES.collect { "$BASE/designs/${it}/${it}.genes.txt" }.join(",")
    produce("results/missing_from_exons.genes.txt", "results/${run_id}_batch_validation.md", "results/${run_id}_batch_validation.html") {
      exec """
          cat ../design/*.genes.txt | python $SCRIPTS/find_missing_genes.py $BASE/designs/genelists/exons.bed > results/missing_from_exons.genes.txt

          if [ -e $BASE/designs/genelists/annovar.bed ]; then
            cat ../design/*.genes.txt | python $SCRIPTS/find_missing_genes.py $BASE/designs/genelists/annovar.bed > results/missing_from_annovar.genes.txt;
          fi

          if [ -e $BASE/designs/genelists/incidentalome.genes.txt ]; then
            python $SCRIPTS/validate_genelists.py --exclude $BASE/designs/genelists/incidentalome.genes.txt $diseaseGeneLists > results/excluded_genes_analyzed.txt;
          fi

          python $SCRIPTS/validate_batch.py --missing_exons results/missing_from_exons.genes.txt --missing_annovar results/missing_from_annovar.genes.txt --excluded_genes results/excluded_genes_analyzed.txt > results/${run_id}_batch_validation.md

          python $SCRIPTS/markdown2.py --extras tables < results/${run_id}_batch_validation.md | python $SCRIPTS/prettify_markdown.py > results/${run_id}_batch_validation.html
      """, "validate_batch"
    }
}

write_run_info = {
    doc "write out all versions that are relevant to this particular run"
    output.dir = "results"

    produce("${run_id}_pipeline_run_info.txt") {
        exec """
            python $SCRIPTS/write_run_info.py --run_id ${run_id} --base "$BASE" > $output.txt
        """
    }
}

create_sample_metadata = {
    doc "Create a new samples.txt file that includes the pipeline ID"
    requires sample_metadata_file : "File describing meta data for pipeline run (usually, samples.txt)"

    output.dir="results"
    produce("results/samples.meta") {
      exec """
          python $SCRIPTS/update_pipeline_run_id.py --id results/run_id --parse True < $sample_metadata_file > results/samples.meta
      """
    }
}

///////////////////////////////////////////////////////////////////
// segments
///////////////////////////////////////////////////////////////////

initialize_batch_run = segment {
    // Check the basic sample information first
    check_sample_info +  // check that fastq files are present
    check_tools +
    update_gene_lists + // build new gene lists by adding sample specific genes to cohort

    // Create a single BED that contains all the regions we want to call variants in
    create_combined_target + 
    create_synonymous_target + // regions where synonymous snvs are not filtered
    build_capture_stats + // how well covered genes are by the capture

    generate_pipeline_id // make a new pipeline run ID file if required
}

finish_batch_run = segment {
   // report on similarity between samples
   sample_similarity_report +

   // check overall quality of results
   validate_batch +

   // write all genelist versions to results
   write_run_info +

   // update metadata and pipeline ID
   create_sample_metadata
}

initialize_profiles = segment {
    set_target_info + 
    create_splice_site_bed
}
